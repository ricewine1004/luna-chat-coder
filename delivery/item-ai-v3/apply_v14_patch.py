from pathlib import Path
import subprocess, sys

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')

# v13 corrected까지 먼저 적용합니다.
if "APP='검은 성흔 Item AI v13'" not in s:
    subprocess.check_call([sys.executable,str(Path(__file__).with_name('apply_v13_runner.py'))])
    s=p.read_text(encoding='utf-8')
if "APP='검은 성흔 Item AI v13'" not in s:
    raise SystemExit('v13 corrected source was not prepared')

# worker 다운로드를 실제 바이트 진행률 + resume/retry + Xet 가속 사용 구조로 교체합니다.
start=s.index('def cmd_download(a):')
end=s.index('\n\ndef load_pipe(a):',start)
new_download=r'''def cmd_download(a):
    import threading
    from huggingface_hub import HfApi, snapshot_download
    root=model_local_path(a.model_id,a.model_dir)
    root.mkdir(parents=True,exist_ok=True)
    os.environ['HF_HUB_DISABLE_SYMLINKS_WARNING']='1'
    # v14: Xet가 설치되어 있으면 사용합니다. local_dir 저장이므로 cache snapshot symlink는 사용하지 않습니다.
    os.environ.pop('HF_HUB_DISABLE_XET',None)

    def visible_downloaded_bytes():
        total=0
        try:
            for f in root.rglob('*'):
                if not f.is_file(): continue
                rel=f.relative_to(root).as_posix().lower()
                # Hugging Face 내부 metadata는 제외하되 현재 내려받는 .incomplete 파일은 포함합니다.
                if '/.cache/huggingface/' in '/'+rel and not f.name.endswith('.incomplete'):
                    continue
                try: total+=f.stat().st_size
                except OSError: pass
        except Exception: pass
        return total

    total_bytes=0
    try:
        info=HfApi().model_info(a.model_id,files_metadata=True)
        total_bytes=sum(int(getattr(x,'size',0) or 0) for x in (info.siblings or []))
    except Exception as e:
        print('DOWNLOAD_METADATA_WARNING='+str(e),flush=True)
    print('DOWNLOAD_TOTAL_BYTES='+str(total_bytes),flush=True)
    print('DOWNLOAD_MODE=XET_OR_HTTP_LOCAL_DIR_RESUME',flush=True)

    stop=threading.Event()
    started=time.time(); last_t=started; last_b=visible_downloaded_bytes()
    def monitor():
        nonlocal last_t,last_b
        while not stop.wait(1.0):
            now=time.time(); done=visible_downloaded_bytes()
            dt=max(0.001,now-last_t); rate=max(0.0,(done-last_b)/dt)
            eta=int(max(0,total_bytes-done)/rate) if total_bytes and rate>1024 else -1
            pct=min(99.9,(done*100.0/total_bytes)) if total_bytes else 0.0
            current=''
            try:
                recent=[]
                for f in root.rglob('*'):
                    if f.is_file() and (f.name.endswith('.incomplete') or '/.cache/huggingface/' not in '/'+f.relative_to(root).as_posix().lower()):
                        try: recent.append((f.stat().st_mtime,f.name))
                        except OSError: pass
                if recent: current=max(recent)[1]
            except Exception: pass
            print(f'DOWNLOAD_PROGRESS={pct:.1f}|{done}|{total_bytes}|{int(rate)}|{eta}|{current}',flush=True)
            last_t,last_b=now,done
    th=threading.Thread(target=monitor,daemon=True);th.start()

    p=None
    try:
        last_error=None
        for attempt in range(1,4):
            try:
                print(f'DOWNLOAD_ATTEMPT={attempt}/3',flush=True)
                p=snapshot_download(
                    repo_id=a.model_id,
                    local_dir=str(root),
                    local_dir_use_symlinks=False,
                    resume_download=True,
                    max_workers=8,
                )
                last_error=None
                break
            except Exception as e:
                last_error=e
                print(f'DOWNLOAD_RETRY={attempt}/3|{e}',flush=True)
                if attempt<3: time.sleep(3)
        if last_error is not None: raise last_error
    finally:
        stop.set();th.join(timeout=2)

    index=Path(p)/'model_index.json'
    if not index.is_file():
        raise RuntimeError('model_index.json 다운로드를 확인하지 못했습니다: '+str(index))
    required=['scheduler','tokenizer','unet','vae']
    missing=[name for name in required if not (Path(p)/name).exists()]
    if missing:
        raise RuntimeError('필수 모델 구성 누락: '+', '.join(missing))
    final_bytes=visible_downloaded_bytes()
    print(f'DOWNLOAD_PROGRESS=100.0|{final_bytes}|{total_bytes}|0|0|완료',flush=True)
    print('MODEL_READY='+str(Path(p).resolve()),flush=True)
'''
s=s[:start]+new_download+s[end:]

# run_worker에서 강제로 끄던 Xet 비활성화를 제거합니다.
s=s.replace(";env['HF_HUB_DISABLE_SYMLINKS_WARNING']='1';env['HF_HUB_DISABLE_XET']='1'", ";env['HF_HUB_DISABLE_SYMLINKS_WARNING']='1'", 1)

# 다운로드 전에 hf_xet을 확인하고 없을 때만 설치합니다.
insert_at='    def download_model(self):\n'
helper=r'''    def _ensure_hf_xet(self,cb):
        try:
            _stream_process([RUNTIME_PY,'-c','import hf_xet; print("HF_XET_READY")'],cb,cwd=ENGINE_ROOT)
            return
        except Exception:
            cb('다운로드 가속 모듈 hf_xet을 최초 1회 설치합니다.')
        _stream_process([RUNTIME_PY,'-m','pip','install','--disable-pip-version-check','--no-warn-script-location','--upgrade','hf_xet>=1.1,<2'],cb,cwd=ENGINE_ROOT)
        _stream_process([RUNTIME_PY,'-c','import hf_xet; print("HF_XET_READY")'],cb,cwd=ENGINE_ROOT)

    @staticmethod
    def _fmt_bytes(n):
        try:n=float(n)
        except Exception:return '0 B'
        units=['B','KB','MB','GB','TB'];i=0
        while n>=1024 and i<len(units)-1:n/=1024;i+=1
        return f'{n:.1f} {units[i]}'

    def _download_line(self,line):
        if line.startswith('DOWNLOAD_TOTAL_BYTES='):
            try:
                total=int(line.split('=',1)[1] or 0)
                self.download_total_bytes=total
                self.after(0,lambda:self.download_progress_text.config(text='전체 모델 용량 확인: '+self._fmt_bytes(total)))
            except Exception:pass
            return
        if line.startswith('DOWNLOAD_PROGRESS='):
            try:
                raw=line.split('=',1)[1];pct,done,total,rate,eta,current=raw.split('|',5)
                pct=float(pct);done=int(done);total=int(total);rate=int(rate);eta=int(eta)
                def update():
                    if self.closing:return
                    self.download_progress_var.set(max(0,min(100,pct)))
                    total_text=self._fmt_bytes(total) if total else '용량 계산 중'
                    eta_text='계산 중' if eta<0 else (f'{eta//60}분 {eta%60}초' if eta>=60 else f'{eta}초')
                    speed=self._fmt_bytes(rate)+'/s' if rate>0 else '계산 중'
                    name=(' / '+current) if current else ''
                    self.download_progress_text.config(text=f'{pct:.1f}%  {self._fmt_bytes(done)} / {total_text}  ·  {speed}  ·  남은 시간 {eta_text}{name}')
                    self.stat('모델 다운로드 중... '+f'{pct:.1f}%')
                self.after(0,update)
            except Exception:pass
            return
        if line.startswith('DOWNLOAD_RETRY='):
            self.after(0,lambda:self.download_progress_text.config(text='네트워크 오류 - 받은 파일을 유지하고 이어받기 재시도 중...'))
            log(line);return
        if line.startswith('DOWNLOAD_MODE='):
            self.after(0,lambda:self.download_progress_text.config(text='가속 다운로드 준비 완료 · 중단 시 이어받기 지원'))
            log(line);return
        log(line)

'''
if insert_at not in s: raise SystemExit('download_model method marker missing')
s=s.replace(insert_at,helper+insert_at,1)

# AI 준비 영역에 다운로드 진행률 UI를 추가합니다.
ui_marker="        self.model_desc=ttk.Label(ready,text='',style='Ready.TLabel',wraplength=340); self.model_desc.pack(anchor='w',pady=(4,0))\n"
ui_add="""        self.download_progress_var=tk.DoubleVar(value=0.0)
        self.download_progress=ttk.Progressbar(ready,variable=self.download_progress_var,maximum=100,mode='determinate'); self.download_progress.pack(fill='x',pady=(8,3))
        self.download_progress_text=ttk.Label(ready,text='모델 다운로드 대기',style='Ready.TLabel',wraplength=340); self.download_progress_text.pack(anchor='w',pady=(0,2))
        self.download_total_bytes=0
"""
if ui_marker not in s: raise SystemExit('model description UI marker missing')
s=s.replace(ui_marker,ui_marker+ui_add,1)

# download_model 메서드를 전체 교체해 이미 설치된 모델은 다운로드를 건너뛰고, 진행률 callback을 사용합니다.
start=s.index('    def download_model(self):')
end=s.index('\n    def prompt(self',start)
new_method=r'''    def download_model(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        self._apply_profile_values(self.cfg.get('model_profile',DEFAULT_PROFILE),save=True)
        if model_ready(self.cfg['model_id'],self.cfg['model_dir']):
            self.download_progress_var.set(100.0)
            self.download_progress_text.config(text='100% · 이미 설치된 공용 모델 사용 · 다운로드 생략')
            self.write('✅ 모델 이미 설치됨 - 다운로드 생략')
            self.refresh_engine();return
        self.download_progress_var.set(0.0);self.download_progress_text.config(text='다운로드 준비 중...')
        def work():
            self.after(0,lambda:self.stat('모델 다운로드 준비 중...'))
            self._ensure_hf_xet(lambda x:log(x))
            result=run_worker(['download','--model-id',self.cfg['model_id'],'--model-dir',self.cfg['model_dir']],self._download_line,'MODEL_READY=')
            if not result: raise RuntimeError('모델 다운로드 완료 경로를 확인하지 못했습니다.')
            if not model_ready(self.cfg['model_id'],self.cfg['model_dir']): raise RuntimeError('모델 파일 검증에 실패했습니다.')
            log('모델 준비 완료: '+result)
            self.after(0,lambda:self.download_progress_var.set(100.0))
            self.after(0,lambda:self.download_progress_text.config(text='100% · 모델 다운로드 및 검증 완료'))
            self.after(0,lambda:self.write('✅ 모델 다운로드 / 확인 완료'))
        self.bg(work)
'''
s=s[:start]+new_method+s[end:]

s=s.replace("APP='검은 성흔 Item AI v13'","APP='검은 성흔 Item AI v14'",1)

required=[
    "APP='검은 성흔 Item AI v14'",
    'DOWNLOAD_TOTAL_BYTES=',
    'DOWNLOAD_PROGRESS=',
    'DOWNLOAD_RETRY=',
    'max_workers=8',
    'hf_xet>=1.1,<2',
    'HF_XET_READY',
    'download_progress_var',
    '이미 설치된 공용 모델 사용',
    'SHARED_MODEL_ROOT',
    '_ACTIVE_PROCESSES=',
    'TerminateJobObject',
    'official Black Stigma dark fantasy ARPG item art direction',
    'vivid ruby red to crimson healing liquid',
]
for marker in required:
    if marker not in s: raise SystemExit('v14 marker missing: '+marker)
if "env['HF_HUB_DISABLE_XET']='1'" in s:
    raise SystemExit('Xet is still forcibly disabled')

p.write_text(s,encoding='utf-8')
print('Item AI v14 accelerated resumable download patch applied')
