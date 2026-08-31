from pathlib import Path

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

old_pip = '''def pip_install(args, cb):
    from pip._internal.cli.main import main as pipmain
    cb('pip ' + ' '.join(args)); rc=pipmain(args)
    if rc: raise RuntimeError(f'패키지 설치 실패 (코드 {rc})')
    importlib.invalidate_caches()
'''
new_pip = '''class _PipLogStream:
    encoding = 'utf-8'
    errors = 'replace'
    def __init__(self, cb):
        self.cb = cb
        self._buf = ''
    def write(self, text):
        if text is None:
            return 0
        text = str(text)
        self._buf += text
        while '\\n' in self._buf:
            line, self._buf = self._buf.split('\\n', 1)
            if line.strip():
                self.cb(line.rstrip())
        return len(text)
    def flush(self):
        if self._buf.strip():
            self.cb(self._buf.rstrip())
        self._buf = ''
    def isatty(self):
        return False


def pip_install(args, cb):
    from pip._internal.cli.main import main as pipmain
    full_args = ['install'] + list(args)
    cb('pip ' + ' '.join(full_args))
    old_out, old_err = sys.stdout, sys.stderr
    stream = _PipLogStream(cb)
    try:
        # PyInstaller --windowed 실행에서는 stdout/stderr가 None일 수 있으므로
        # pip가 콘솔에 직접 쓰지 못하게 안전한 로그 스트림으로 교체합니다.
        sys.stdout = stream
        sys.stderr = stream
        rc = pipmain(full_args)
        stream.flush()
    finally:
        sys.stdout = old_out
        sys.stderr = old_err
    if rc:
        raise RuntimeError(f'패키지 설치 실패 (코드 {rc})')
    importlib.invalidate_caches()
'''
if old_pip not in s:
    raise SystemExit('pip_install block not found')
s = s.replace(old_pip, new_pip)

old_left = "        left=ttk.Frame(body,style='Panel.TFrame',padding=14,width=390); left.pack(side='left',fill='y'); left.pack_propagate(False)\n        right=ttk.Frame(body,padding=(14,0,0,0)); right.pack(side='left',fill='both',expand=True)\n"
new_left = "        left_shell=ttk.Frame(body,style='Panel.TFrame',width=400); left_shell.pack(side='left',fill='y'); left_shell.pack_propagate(False)\n        left_canvas=tk.Canvas(left_shell,bg='#20232b',highlightthickness=0,width=378)\n        left_scroll=ttk.Scrollbar(left_shell,orient='vertical',command=left_canvas.yview)\n        left_canvas.configure(yscrollcommand=left_scroll.set)\n        left_scroll.pack(side='right',fill='y'); left_canvas.pack(side='left',fill='both',expand=True)\n        left=ttk.Frame(left_canvas,style='Panel.TFrame',padding=14)\n        left_window=left_canvas.create_window((0,0),window=left,anchor='nw')\n        def _left_region(event=None):\n            left_canvas.configure(scrollregion=left_canvas.bbox('all'))\n        def _left_width(event):\n            left_canvas.itemconfigure(left_window,width=max(250,event.width))\n        def _wheel(event):\n            left_canvas.yview_scroll(int(-1*(event.delta/120)), 'units')\n        left.bind('<Configure>',_left_region); left_canvas.bind('<Configure>',_left_width)\n        left_canvas.bind('<Enter>',lambda e:left_canvas.bind_all('<MouseWheel>',_wheel))\n        left_canvas.bind('<Leave>',lambda e:left_canvas.unbind_all('<MouseWheel>'))\n        right=ttk.Frame(body,padding=(14,0,0,0)); right.pack(side='left',fill='both',expand=True)\n"
if old_left not in s:
    raise SystemExit('left panel block not found')
s = s.replace(old_left, new_left)

s = s.replace("APP='검은 성흔 Item AI v3'", "APP='검은 성흔 Item AI v4'")

# CI에서 실제 수정 여부를 확인할 수 있는 표식
if "full_args = ['install'] + list(args)" not in s:
    raise SystemExit('pip install fix missing')
if "left_scroll=ttk.Scrollbar" not in s:
    raise SystemExit('scrollbar fix missing')
if "AI 엔진 설치 / 복구" not in s or "모델 다운로드 / 확인" not in s:
    raise SystemExit('required AI preparation buttons missing')
if "self.protocol('WM_DELETE_WINDOW', self.on_close)" not in s:
    raise SystemExit('clean shutdown handler missing')

p.write_text(s, encoding='utf-8')
print('Item AI v4 patch applied')
