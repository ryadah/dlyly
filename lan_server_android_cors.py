# -*- coding: utf-8 -*-
"""خادم الدليل الطبي - شبكة LAN
يشغّل الصفحة ويخزن قاعدة البيانات المركزية في SQLite.
مصمم للعمل بالمكتبة القياسية فقط، لذلك لا يحتاج pip أو حزم خارجية.
"""
import json, os, sqlite3, threading, shutil, datetime, sys, gzip
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

HOST = '0.0.0.0'
PORT = 8000
ROOT = os.path.dirname(os.path.abspath(__file__))
DB_PATH = os.path.join(ROOT, 'medical_directory.db')
BACKUP_DIR = os.path.join(ROOT, 'backups')
INDEX_FILE = 'الدليل_الطبي.html'
LOCK = threading.RLock()

EMPTY_STATE = {"revision": 0, "services": [], "serviceTypes": [], "users": [], "changeLog": [], "messages": []}

def now_iso():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()

def db_connect():
    c = sqlite3.connect(DB_PATH, timeout=30)
    c.execute('PRAGMA journal_mode=WAL')
    c.execute('PRAGMA synchronous=NORMAL')
    c.execute('CREATE TABLE IF NOT EXISTS app_state (id INTEGER PRIMARY KEY CHECK(id=1), revision INTEGER NOT NULL, data TEXT NOT NULL, updated_at TEXT NOT NULL)')
    c.commit()
    return c

def load_state():
    with LOCK:
        c = db_connect()
        row = c.execute('SELECT revision, data FROM app_state WHERE id=1').fetchone()
        c.close()
        if not row:
            return None
        try:
            state = json.loads(row[1])
            if not isinstance(state, dict): return None
            state['revision'] = int(row[0])
            return state
        except Exception:
            return None

def save_state(payload):
    if not isinstance(payload, dict):
        raise ValueError('invalid state')
    state = {
        'services': payload.get('services') if isinstance(payload.get('services'), list) else [],
        'serviceTypes': payload.get('serviceTypes') if isinstance(payload.get('serviceTypes'), list) else [],
        'users': payload.get('users') if isinstance(payload.get('users'), list) else [],
        'changeLog': payload.get('changeLog') if isinstance(payload.get('changeLog'), list) else [],
        'messages': payload.get('messages') if isinstance(payload.get('messages'), list) else [],
    }
    with LOCK:
        c = db_connect()
        row = c.execute('SELECT revision FROM app_state WHERE id=1').fetchone()
        rev = int(row[0]) if row else 0
        rev += 1
        state['revision'] = rev
        text = json.dumps(state, ensure_ascii=False, separators=(',', ':'))
        c.execute('INSERT INTO app_state(id,revision,data,updated_at) VALUES(1,?,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,data=excluded.data,updated_at=excluded.updated_at', (rev,text,now_iso()))
        c.commit(); c.close()
        make_backup_if_needed(rev, text)
        return state

def make_backup_if_needed(rev, text):
    # نسخة احتياطية كل 20 عملية حفظ، مع الاحتفاظ بآخر 20 نسخة.
    if rev % 20 != 0: return
    os.makedirs(BACKUP_DIR, exist_ok=True)
    fn = os.path.join(BACKUP_DIR, 'backup_%06d.json' % rev)
    try:
        with open(fn, 'w', encoding='utf-8') as f: f.write(text)
        files = sorted([x for x in os.listdir(BACKUP_DIR) if x.endswith('.json')])
        for old in files[:-20]:
            try: os.remove(os.path.join(BACKUP_DIR, old))
            except OSError: pass
    except OSError:
        pass

def _gzip_bytes(data):
    return gzip.compress(data, compresslevel=6)

class Handler(SimpleHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=ROOT, **kwargs)

    def _send_json(self, code, obj):
        data = json.dumps(obj, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
        use_gzip = 'gzip' in self.headers.get('Accept-Encoding','').lower()
        out = _gzip_bytes(data) if use_gzip else data
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(out)))
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Cache-Control')
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
        if use_gzip: self.send_header('Content-Encoding','gzip')
        self.end_headers(); self.wfile.write(out)

    def _handle_admin_update_upload(self):
        """استقبال ملف تحديث خام من المدير وحفظه باسم ثابت مع زيادة رقم الإصدار تلقائيًا."""
        query = parse_qs(urlparse(self.path).query)
        kind = (query.get('type', [''])[0] or self.headers.get('X-Update-Type', '')).strip().lower()
        if kind not in ('html', 'css', 'apk'):
            self._send_json(400, {'ok': False, 'message': 'نوع تحديث غير صالح'})
            return
        try:
            length = int(self.headers.get('Content-Length', '0'))
        except ValueError:
            length = 0
        if length <= 0:
            self._send_json(400, {'ok': False, 'message': 'لم يتم إرسال ملف'})
            return
        max_size = 100 * 1024 * 1024 if kind == 'apk' else 10 * 1024 * 1024
        if length > max_size:
            self._send_json(413, {'ok': False, 'message': 'حجم الملف أكبر من الحد المسموح'})
            return
        filenames = {'html': 'الدليل_الطبي.html', 'css': 'bootstrap.min.css', 'apk': 'app-update.apk'}
        fields = {'html': ('htmlVersion', 'htmlUrl'), 'css': ('cssVersion', 'cssUrl'), 'apk': ('apkVersionCode', 'apkUrl')}
        os.makedirs(os.path.join(ROOT, 'updates'), exist_ok=True)
        target = os.path.join(ROOT, 'updates', filenames[kind])
        tmp = target + '.uploading'
        try:
            remaining = length
            with open(tmp, 'wb') as out:
                while remaining > 0:
                    chunk = self.rfile.read(min(1024 * 1024, remaining))
                    if not chunk: raise ConnectionError('انقطع رفع الملف')
                    out.write(chunk); remaining -= len(chunk)
            if os.path.getsize(tmp) == 0: raise ValueError('الملف فارغ')
            os.replace(tmp, target)
            update_file = os.path.join(ROOT, 'update.json')
            info = {}
            if os.path.isfile(update_file):
                try:
                    with open(update_file, 'r', encoding='utf-8') as f: loaded = json.load(f)
                    if isinstance(loaded, dict): info.update(loaded)
                except Exception: pass
            version_key, url_key = fields[kind]
            current = int(info.get(version_key, 0) or 0)
            new_version = current + 1
            info[version_key] = new_version
            if kind == 'apk': info['apkVersionName'] = str(info.get('apkVersionName') or ('auto-' + str(new_version)))
            info[url_key] = '/updates/' + filenames[kind]
            with open(update_file, 'w', encoding='utf-8') as f:
                json.dump(info, f, ensure_ascii=False, indent=2)
            labels = {'html': 'الصفحة', 'css': 'التنسيق', 'apk': 'التطبيق'}
            self._send_json(200, {'ok': True, 'message': 'تم رفع تحديث ' + labels[kind] + ' بنجاح. الإصدار: ' + str(new_version), 'type': kind, 'version': new_version, 'filename': filenames[kind]})
        except Exception as e:
            try:
                if os.path.exists(tmp): os.remove(tmp)
            except OSError: pass
            self._send_json(500, {'ok': False, 'message': 'فشل رفع التحديث: ' + str(e)})

    def do_GET(self):
        path = urlparse(self.path).path
        # افتح صفحة الدليل الطبي مباشرة عند الدخول إلى عنوان الخادم
        # بدل عرض قائمة ملفات المجلد.
        if path in ('/', '/index.html'):
            try:
                with open(os.path.join(ROOT, INDEX_FILE), 'rb') as f:
                    data = f.read()
                use_gzip = 'gzip' in self.headers.get('Accept-Encoding','').lower()
                out = _gzip_bytes(data) if use_gzip else data
                self.send_response(200)
                self.send_header('Content-Type', 'text/html; charset=utf-8')
                self.send_header('Content-Length', str(len(out)))
                self.send_header('Cache-Control', 'no-cache, must-revalidate')
                if use_gzip: self.send_header('Content-Encoding','gzip')
                self.end_headers()
                self.wfile.write(out)
            except OSError:
                self.send_error(404, 'Medical Directory page not found')
            return
        if path == '/api/state':
            state = load_state()
            if state is None:
                self._send_json(404, {'error':'state_not_initialized'})
                return
            # طلبات المزامنة الدورية لا تحتاج إلى إعادة إرسال قاعدة البيانات
            # كاملة كل مرة. إذا كانت نسخة العميل مساوية للنسخة الحالية نعيد
            # استجابة فارغة خفيفة جدًا، وهذا يقلل استهلاك الشبكة ويمنع البطء.
            try:
                client_revision = int((urlparse(self.path).query or '').split('revision=', 1)[1].split('&', 1)[0])
            except Exception:
                client_revision = -1
            if client_revision >= int(state.get('revision', 0)) and client_revision >= 0:
                self.send_response(204)
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Content-Length', '0')
                self.end_headers()
                return
            self._send_json(200, state)
            return
        if path == '/api/health':
            self._send_json(200, {'ok':True, 'revision': (load_state() or EMPTY_STATE)['revision']})
            return
        if path == '/api/info':
            self._send_json(200, {'ok':True,'name':'الدليل الطبي','port':PORT,'root':ROOT,'python':sys.version.split()[0]})
            return
        if path == '/api/update':
            update_file = os.path.join(ROOT, 'update.json')
            info = {
                'htmlVersion': 1, 'htmlUrl': '',
                'cssVersion': 1, 'cssUrl': '',
                'apkVersionCode': 0, 'apkVersionName': '', 'apkUrl': '',
            }
            try:
                loaded = {}
                if os.path.isfile(update_file):
                    with open(update_file, 'r', encoding='utf-8') as f:
                        loaded = json.load(f)
                    if isinstance(loaded, dict): info.update(loaded)
                host = self.headers.get('Host', 'localhost:%d' % PORT)
                scheme = 'http'
                def abs_url(value, default=''):
                    if not value: return default
                    value = str(value)
                    if value.startswith('http://') or value.startswith('https://'): return value
                    path_value = '/' + value.lstrip('/')
                    from urllib.parse import quote
                    encoded = quote(path_value, safe='/%:')
                    return scheme + '://' + host + encoded
                html_path = os.path.join(ROOT, 'updates', 'الدليل_الطبي.html')
                css_path = os.path.join(ROOT, 'updates', 'bootstrap.min.css')
                apk_path = os.path.join(ROOT, 'updates', 'app-update.apk')
                if not info.get('htmlUrl') and os.path.isfile(html_path): info['htmlUrl'] = '/updates/الدليل_الطبي.html'
                if not info.get('cssUrl') and os.path.isfile(css_path): info['cssUrl'] = '/updates/bootstrap.min.css'
                if not info.get('apkUrl') and os.path.isfile(apk_path): info['apkUrl'] = '/updates/app-update.apk'
                info['htmlUrl'] = abs_url(info.get('htmlUrl'))
                info['cssUrl'] = abs_url(info.get('cssUrl'))
                info['apkUrl'] = abs_url(info.get('apkUrl'))
                info['htmlAvailable'] = bool(info.get('htmlUrl') and os.path.isfile(html_path))
                info['cssAvailable'] = bool(info.get('cssUrl') and os.path.isfile(css_path))
                info['apkAvailable'] = bool(info.get('apkUrl') and os.path.isfile(apk_path))
                self._send_json(200, info)
            except Exception as e:
                self._send_json(200, {'htmlVersion':1,'htmlUrl':'','cssVersion':1,'cssUrl':'','apkVersionCode':0,'apkVersionName':'','apkUrl':'','htmlAvailable':False,'cssAvailable':False,'apkAvailable':False,'error':str(e)})
            return
        super().do_GET()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type, Cache-Control')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_POST(self):
        if urlparse(self.path).path == '/api/admin/upload-update':
            self._handle_admin_update_upload()
            return

        path = urlparse(self.path).path
        if path != '/api/state':
            self._send_json(404, {'error':'not_found'}); return
        try:
            n = int(self.headers.get('Content-Length','0'))
            if n <= 0 or n > 80 * 1024 * 1024:
                self._send_json(413, {'error':'payload_too_large'}); return
            raw = self.rfile.read(n)
            payload = json.loads(raw.decode('utf-8'))
            state = save_state(payload)
            self._send_json(200, state)
        except Exception as e:
            self._send_json(400, {'error':'invalid_state','detail':str(e)})

    def log_message(self, fmt, *args):
        print('[%s] %s' % (datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S'), fmt % args))

def main():
    os.makedirs(BACKUP_DIR, exist_ok=True)
    print('=' * 62)
    print('الدليل الطبي - خادم الشبكة المحلية')
    print('Python:', sys.version.split()[0])
    print('المجلد:', ROOT)
    print('العنوان: http://localhost:%d' % PORT)
    print('لأجهزة الشبكة: http://IP-الخادم:%d' % PORT)
    print('قاعدة البيانات:', DB_PATH)
    print('إيقاف الخادم: Ctrl+C')
    print('=' * 62)
    try:
        server = ThreadingHTTPServer((HOST, PORT), Handler)
    except OSError as e:
        print('\nتعذر فتح المنفذ %d: %s' % (PORT, e))
        print('تأكد من عدم تشغيل خادم آخر على نفس المنفذ.')
        input('اضغط Enter للخروج...')
        return 1
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print('\nتم إيقاف الخادم.')
    finally:
        server.server_close()
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
