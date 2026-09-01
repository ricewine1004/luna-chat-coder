from pathlib import Path
import subprocess
import sys

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v12'" not in s:
    subprocess.check_call([sys.executable,str(Path(__file__).with_name('apply_v12_patch.py'))])
    s=p.read_text(encoding='utf-8')
if "APP='검은 성흔 Item AI v12'" not in s:
    raise SystemExit('v12 source was not prepared')

# ctypes를 추가해 Windows Job Object로 외부 Python 프로세스 트리를 관리합니다.
s=s.replace(
    'import os, sys, json, time, random, threading, traceback, importlib.util, gc, shutil, subprocess, urllib.request, zipfile',
    'import os, sys, json, time, random, threading, traceback, importlib.util, gc, shutil, subprocess, urllib.request, zipfile, ctypes, atexit',
    1,
)

insert="GET_PIP_URL='https://bootstrap.pypa.io/get-pip.py'\n"
tracker=r'''

_ACTIVE_PROCESSES={}
_ACTIVE_PROCESS_LOCK=threading.RLock()
_JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE=0x00002000
_JobObjectExtendedLimitInformation=9

class _JOBOBJECT_BASIC_LIMIT_INFORMATION(ctypes.Structure):
    _fields_=[
        ('PerProcessUserTimeLimit',ctypes.c_longlong),
        ('PerJobUserTimeLimit',ctypes.c_longlong),
        ('LimitFlags',ctypes.c_uint32),
        ('MinimumWorkingSetSize',ctypes.c_size_t),
        ('MaximumWorkingSetSize',ctypes.c_size_t),
        ('ActiveProcessLimit',ctypes.c_uint32),
        ('Affinity',ctypes.c_size_t),
        ('PriorityClass',ctypes.c_uint32),
        ('SchedulingClass',ctypes.c_uint32),
    ]

class _IO_COUNTERS(ctypes.Structure):
    _fields_=[
        ('ReadOperationCount',ctypes.c_uint64),('WriteOperationCount',ctypes.c_uint64),('OtherOperationCount',ctypes.c_uint64),
        ('ReadTransferCount',ctypes.c_uint64),('WriteTransferCount',ctypes.c_uint64),('OtherTransferCount',ctypes.c_uint64),
    ]

class _JOBOBJECT_EXTENDED_LIMIT_INFORMATION(ctypes.Structure):
    _fields_=[
        ('BasicLimitInformation',_JOBOBJECT_BASIC_LIMIT_INFORMATION),
        ('IoInfo',_IO_COUNTERS),
        ('ProcessMemoryLimit',ctypes.c_size_t),
        ('JobMemoryLimit',ctypes.c_size_t),
        ('PeakProcessMemoryUsed',ctypes.c_size_t),
        ('PeakJobMemoryUsed',ctypes.c_size_t),
    ]


def _create_kill_job_for_process(proc):
    if os.name!='nt':
        return None
    try:
        k32=ctypes.WinDLL('kernel32',use_last_error=True)
        k32.CreateJobObjectW.argtypes=[ctypes.c_void_p,ctypes.c_wchar_p]
        k32.CreateJobObjectW.restype=ctypes.c_void_p
        k32.SetInformationJobObject.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p,ctypes.c_uint32]
        k32.SetInformationJobObject.restype=ctypes.c_int
        k32.AssignProcessToJobObject.argtypes=[ctypes.c_void_p,ctypes.c_void_p]
        k32.AssignProcessToJobObject.restype=ctypes.c_int
        job=k32.CreateJobObjectW(None,None)
        if not job:
            return None
        info=_JOBOBJECT_EXTENDED_LIMIT_INFORMATION()
        info.BasicLimitInformation.LimitFlags=_JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        ok=k32.SetInformationJobObject(job,_JobObjectExtendedLimitInformation,ctypes.byref(info),ctypes.sizeof(info))
        if not ok:
            k32.CloseHandle(ctypes.c_void_p(job)); return None
        ph=ctypes.c_void_p(int(proc._handle))
        ok=k32.AssignProcessToJobObject(ctypes.c_void_p(job),ph)
        if not ok:
            k32.CloseHandle(ctypes.c_void_p(job)); return None
        return int(job)
    except Exception as e:
        log('Job Object 생성 실패(terminate fallback 사용): '+str(e))
        return None


def _track_process(proc,label='external'):
    job=_create_kill_job_for_process(proc)
    with _ACTIVE_PROCESS_LOCK:
        _ACTIVE_PROCESSES[proc.pid]=(proc,job,label)
    log(f'외부 프로세스 등록: {label} PID={proc.pid}')
    return proc


def _close_job_handle(job):
    if not job or os.name!='nt': return
    try:
        ctypes.WinDLL('kernel32',use_last_error=True).CloseHandle(ctypes.c_void_p(job))
    except Exception: pass


def _untrack_process(proc):
    with _ACTIVE_PROCESS_LOCK:
        entry=_ACTIVE_PROCESSES.pop(getattr(proc,'pid',-1),None)
    if entry:
        _close_job_handle(entry[1])
        log(f'외부 프로세스 해제: {entry[2]} PID={proc.pid}')


def _terminate_tracked_entry(proc,job,label):
    if proc.poll() is not None:
        _close_job_handle(job); return
    log(f'외부 프로세스 트리 종료 시작: {label} PID={proc.pid}')
    if os.name=='nt' and job:
        try:
            k32=ctypes.WinDLL('kernel32',use_last_error=True)
            k32.TerminateJobObject.argtypes=[ctypes.c_void_p,ctypes.c_uint32]
            k32.TerminateJobObject.restype=ctypes.c_int
            k32.TerminateJobObject(ctypes.c_void_p(job),1)
        except Exception as e:
            log('Job Object 종료 오류: '+str(e))
    else:
        try: proc.terminate()
        except Exception: pass
    try: proc.wait(timeout=3)
    except Exception:
        try: proc.kill()
        except Exception: pass
        try: proc.wait(timeout=2)
        except Exception: pass
    _close_job_handle(job)
    log(f'외부 프로세스 트리 종료 완료: {label} PID={proc.pid} returncode={proc.poll()}')


def terminate_all_external_processes():
    with _ACTIVE_PROCESS_LOCK:
        entries=list(_ACTIVE_PROCESSES.values())
        _ACTIVE_PROCESSES.clear()
    for proc,job,label in entries:
        _terminate_tracked_entry(proc,job,label)
    gc.collect()


atexit.register(terminate_all_external_processes)
'''
if insert not in s: raise SystemExit('process tracker insertion point missing')
s=s.replace(insert,insert+tracker,1)

# 설치/런타임 프로세스도 추적하도록 _stream_process 전체를 교체합니다.
start=s.index('def _stream_process(cmd, cb, cwd=None):')
end=s.index('\n\ndef _download_file(',start)
new_stream=r'''def _stream_process(cmd, cb, cwd=None):
    cb('실행: ' + ' '.join(str(x) for x in cmd))
    env=os.environ.copy()
    env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8';env['PIP_DISABLE_PIP_VERSION_CHECK']='1'
    proc = subprocess.Popen(
        [str(x) for x in cmd],
        cwd=str(cwd) if cwd else None,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding='utf-8',
        errors='replace',
        **_hidden_subprocess_kwargs(),
    )
    _track_process(proc,'install/runtime')
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            line=line.rstrip()
            if line: cb(line)
        rc=proc.wait()
        if rc!=0: raise RuntimeError(f'외부 설치 프로세스 실패 (코드 {rc})')
        return rc
    finally:
        try:
            if proc.stdout: proc.stdout.close()
        except Exception: pass
        _untrack_process(proc)
'''
s=s[:start]+new_stream+s[end:]

# AI worker도 등록/해제하여 종료 시 즉시 프로세스 트리를 제거합니다.
start=s.index('def run_worker(args, cb, result_prefix=None):')
# 다음 클래스 App 직전까지 run_worker가 마지막 worker 함수입니다.
end=s.index('\nclass App(tk.Tk):',start)
old_tail=s[start:end]
# preserve any helpers after run_worker? v12 worker block has run_worker as last function, so replace only function to end marker.
new_run=r'''def run_worker(args, cb, result_prefix=None):
    ensure_runtime(cb)
    ensure_worker()
    cmd=[RUNTIME_PY,WORKER]+list(args)
    cb('AI 작업 프로세스 실행: '+str(args[0]))
    env=os.environ.copy();env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8';env['PIP_DISABLE_PIP_VERSION_CHECK']='1';env['HF_HUB_DISABLE_SYMLINKS_WARNING']='1';env['HF_HUB_DISABLE_XET']='1'
    proc=subprocess.Popen([str(x) for x in cmd],cwd=str(DATA),env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',**_hidden_subprocess_kwargs())
    _track_process(proc,'AI worker '+str(args[0]))
    result=None
    try:
        assert proc.stdout is not None
        for line in proc.stdout:
            line=line.rstrip()
            if not line: continue
            cb(line)
            if result_prefix and line.startswith(result_prefix):
                result=line[len(result_prefix):]
        rc=proc.wait()
        if rc!=0: raise RuntimeError(f'AI 작업 프로세스 실패 (코드 {rc})')
        return result
    finally:
        try:
            if proc.stdout: proc.stdout.close()
        except Exception: pass
        _untrack_process(proc)
'''
# retain text before run_worker? replacement range only run_worker to class, should be safe.
s=s[:start]+new_run+s[end:]

# 종료 시 프로세스 트리를 메모리/GUI 정리보다 먼저 종료합니다.
start=s.index('    def on_close(self):')
end=s.index('\nif __name__==\'__main__\':',start)
new_close=r'''    def on_close(self):
        if self.closing:return
        self.closing=True; log('종료 시작 - 새 작업 차단')
        try:self.status.config(text='외부 AI 프로세스 종료 중...');self.update_idletasks()
        except Exception:pass
        try:
            terminate_all_external_processes()
            log('모든 외부 Python/AI 프로세스 종료 완료')
        except Exception as e:
            log('외부 프로세스 종료 오류: '+str(e))
        try:
            self.pipe=None;self.pipe_id=None
            self.images=[None]*4;self.meta=[None]*4;self.refs=[None]*4
            gc.collect();log('이미지/GUI/AI 참조 해제 완료')
        except Exception as e:log('참조 해제 오류: '+str(e))
        try:self.quit();self.destroy()
        finally:
            gc.collect();log('프로그램 종료 완료')
'''
s=s[:start]+new_close+s[end:]

s=s.replace("APP='검은 성흔 Item AI v12'","APP='검은 성흔 Item AI v13'",1)

required=[
    "APP='검은 성흔 Item AI v13'",
    '_ACTIVE_PROCESSES={}',
    '_JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE',
    'AssignProcessToJobObject',
    'TerminateJobObject',
    "_track_process(proc,'AI worker ",
    "_track_process(proc,'install/runtime')",
    'terminate_all_external_processes()',
    'atexit.register(terminate_all_external_processes)',
    'BlackStigmaItemAI_Models',
    'official Black Stigma dark fantasy ARPG item art direction',
    'vivid ruby red to crimson healing liquid',
    '✅ 이미지 4장 생성 완료',
]
for marker in required:
    if marker not in s: raise SystemExit('v13 marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Item AI v13 complete process shutdown patch applied')
