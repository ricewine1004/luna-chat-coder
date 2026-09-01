from pathlib import Path

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

# v4 패치가 아직 적용되지 않은 소스에 먼저 적용합니다.
if "APP='검은 성흔 Item AI v3'" in s:
    ns = {}
    exec(Path(__file__).with_name('apply_v4_patch.py').read_text(encoding='utf-8'), ns, ns)
    s = p.read_text(encoding='utf-8')

# 필요한 표준 라이브러리 import 추가
s = s.replace(
    "import os, sys, json, time, random, threading, traceback, importlib.util, gc, shutil, subprocess",
    "import os, sys, json, time, random, threading, traceback, importlib.util, gc, shutil, subprocess, urllib.request, zipfile"
)

# 전용 Python 런타임 경로 추가
needle = "PKG=DATA/'engine_packages'; PKG.mkdir(exist_ok=True)\n"
replacement = "PKG=DATA/'engine_packages'; PKG.mkdir(exist_ok=True)\nRUNTIME=DATA/'python_runtime'\nRUNTIME_PY=RUNTIME/'python.exe'\nRUNTIME_VERSION='3.11.9'\nRUNTIME_ZIP_URL=f'https://www.python.org/ftp/python/{RUNTIME_VERSION}/python-{RUNTIME_VERSION}-embed-amd64.zip'\nGET_PIP_URL='https://bootstrap.pypa.io/get-pip.py'\n"
if needle not in s:
    raise SystemExit('runtime insertion point not found')
s = s.replace(needle, replacement, 1)

start = s.index('class _PipLogStream:')
end = s.index('\nclass App(tk.Tk):', start)
new_block = r'''def _hidden_subprocess_kwargs():
    kw = {}
    if os.name == 'nt':
        kw['creationflags'] = getattr(subprocess, 'CREATE_NO_WINDOW', 0x08000000)
    return kw


def _stream_process(cmd, cb, cwd=None):
    cb('실행: ' + ' '.join(str(x) for x in cmd))
    proc = subprocess.Popen(
        [str(x) for x in cmd],
        cwd=str(cwd) if cwd else None,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding='utf-8',
        errors='replace',
        **_hidden_subprocess_kwargs(),
    )
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip()
        if line:
            cb(line)
    rc = proc.wait()
    if rc != 0:
        raise RuntimeError(f'외부 설치 프로세스 실패 (코드 {rc})')
    return rc


def _download_file(url, dest, cb, label):
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + '.part')
    cb(f'{label} 다운로드 시작: {url}')
    req = urllib.request.Request(url, headers={'User-Agent':'BlackStigmaItemAI/5'})
    with urllib.request.urlopen(req, timeout=60) as r, tmp.open('wb') as f:
        total = int(r.headers.get('Content-Length') or 0)
        done = 0
        last_pct = -1
        while True:
            chunk = r.read(1024 * 1024)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                pct = int(done * 100 / total)
                if pct >= last_pct + 10:
                    last_pct = pct
                    cb(f'{label} 다운로드 {pct}% ({done/1024/1024:.1f} MB / {total/1024/1024:.1f} MB)')
    tmp.replace(dest)
    cb(f'{label} 다운로드 완료: {dest}')


def ensure_runtime(cb):
    if RUNTIME_PY.exists():
        # 런타임 자체가 실제 실행 가능한지 확인합니다.
        try:
            _stream_process([RUNTIME_PY, '-c', 'import sys; print(sys.version)'], cb)
            return
        except Exception:
            cb('기존 Python 런타임이 손상되어 다시 준비합니다.')
            shutil.rmtree(RUNTIME, ignore_errors=True)

    DATA.mkdir(parents=True, exist_ok=True)
    runtime_zip = DATA / f'python-{RUNTIME_VERSION}-embed-amd64.zip'
    get_pip = DATA / 'get-pip.py'
    _download_file(RUNTIME_ZIP_URL, runtime_zip, cb, 'Python 런타임')

    RUNTIME.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(runtime_zip, 'r') as zf:
        zf.extractall(RUNTIME)

    # embeddable Python은 기본적으로 site 모듈 로딩이 비활성화되어 있으므로 pip 사용을 위해 활성화합니다.
    pth_files = list(RUNTIME.glob('python*._pth'))
    if not pth_files:
        raise RuntimeError('Python 런타임 설정 파일(*._pth)을 찾지 못했습니다.')
    pth = pth_files[0]
    text = pth.read_text(encoding='utf-8')
    if '#import site' in text:
        text = text.replace('#import site', 'import site')
    elif 'import site' not in text:
        text += '\nimport site\n'
    pth.write_text(text, encoding='utf-8')

    _download_file(GET_PIP_URL, get_pip, cb, 'pip 설치 도구')
    _stream_process([RUNTIME_PY, get_pip, '--disable-pip-version-check', '--no-warn-script-location'], cb, cwd=DATA)
    _stream_process([RUNTIME_PY, '-m', 'pip', '--version'], cb)
    cb('프로그램 전용 Python/pip 런타임 준비 완료')


def pip_install(args, cb):
    ensure_runtime(cb)
    full_args = [RUNTIME_PY, '-m', 'pip', 'install'] + list(args)
    _stream_process(full_args, cb, cwd=DATA)
    importlib.invalidate_caches()
'''
s = s[:start] + new_block + s[end:]

# 엔진 설치 전에 불완전한 패키지 타겟을 정리하고, 외부 Python pip를 사용하게 유지합니다.
old = "        def work():\n            self.after(0,lambda:self.stat('AI 엔진 설치 중...')); cb=lambda s: log(s)\n            common=['--disable-pip-version-check','--no-warn-script-location','--upgrade','--target',str(PKG)]\n"
new = "        def work():\n            self.after(0,lambda:self.stat('AI 엔진 설치 중...')); cb=lambda s: log(s)\n            if PKG.exists() and not engine_ready():\n                cb('이전 실패 설치 흔적을 정리합니다: '+str(PKG))\n                shutil.rmtree(PKG, ignore_errors=True)\n                PKG.mkdir(parents=True, exist_ok=True)\n            common=['--disable-pip-version-check','--no-warn-script-location','--upgrade','--target',str(PKG)]\n"
if old not in s:
    raise SystemExit('install_engine block not found')
s = s.replace(old, new, 1)

# 버전 표시
s = s.replace("APP='검은 성흔 Item AI v4'", "APP='검은 성흔 Item AI v5'")

# 검증 표식
required = [
    "RUNTIME_PY=RUNTIME/'python.exe'",
    "python-{RUNTIME_VERSION}-embed-amd64.zip",
    "GET_PIP_URL='https://bootstrap.pypa.io/get-pip.py'",
    "[RUNTIME_PY, '-m', 'pip', 'install']",
    "subprocess.CREATE_NO_WINDOW",
    "left_scroll=ttk.Scrollbar",
    "AI 엔진 설치 / 복구",
    "모델 다운로드 / 확인",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s:
        raise SystemExit('v5 required marker missing: ' + marker)

p.write_text(s, encoding='utf-8')
print('Item AI v5 patch applied')
