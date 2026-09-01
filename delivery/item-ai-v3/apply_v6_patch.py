from pathlib import Path
import subprocess
import sys

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

# v5까지 순서대로 적용
if "APP='검은 성흔 Item AI v3'" in s or "APP='검은 성흔 Item AI v4'" in s:
    subprocess.check_call([sys.executable, str(Path(__file__).with_name('apply_v5_patch.py'))])
    s = p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v5'" not in s:
    raise SystemExit('v5 source was not prepared')

# 긴 실행 폴더 아래가 아닌 현재 드라이브 루트의 짧은 엔진 경로를 사용합니다.
old_runtime = "RUNTIME=DATA/'python_runtime'\nRUNTIME_PY=RUNTIME/'python.exe'\n"
new_runtime = "ENGINE_ROOT=(Path(BASE.anchor)/'BlackStigmaItemAI_Engine') if BASE.anchor else (DATA/'engine')\nRUNTIME=ENGINE_ROOT/'py311'\nRUNTIME_PY=RUNTIME/'python.exe'\nRUNTIME_SITE=RUNTIME/'Lib'/'site-packages'\n"
if old_runtime not in s:
    raise SystemExit('runtime path block not found')
s = s.replace(old_runtime, new_runtime, 1)

# 외부 Python 출력이 한글 경로에서도 깨지지 않도록 UTF-8 환경을 강제합니다.
old_popen = "    proc = subprocess.Popen(\n        [str(x) for x in cmd],\n        cwd=str(cwd) if cwd else None,\n        stdout=subprocess.PIPE,\n"
new_popen = "    env=os.environ.copy()\n    env['PYTHONUTF8']='1'\n    env['PYTHONIOENCODING']='utf-8'\n    env['PIP_DISABLE_PIP_VERSION_CHECK']='1'\n    proc = subprocess.Popen(\n        [str(x) for x in cmd],\n        cwd=str(cwd) if cwd else None,\n        env=env,\n        stdout=subprocess.PIPE,\n"
if old_popen not in s:
    raise SystemExit('subprocess block not found')
s = s.replace(old_popen, new_popen, 1)

# GUI 프로세스에서는 설치 상태를 전용 Python 자체의 import 검사로 판단합니다.
old_ready = "def engine_ready(): return all(installed(x) for x in ['torch','diffusers','transformers','huggingface_hub','accelerate','safetensors'])\n"
new_ready = r'''def activate_runtime_packages():
    if RUNTIME_SITE.exists():
        sp=str(RUNTIME_SITE)
        if sp not in sys.path:
            # PyInstaller에 포함된 GUI/Pillow 모듈을 우선 사용하고 AI 패키지는 뒤에서 찾습니다.
            sys.path.append(sp)
    try:
        torch_lib=RUNTIME_SITE/'torch'/'lib'
        if os.name=='nt' and torch_lib.exists() and hasattr(os,'add_dll_directory'):
            os.add_dll_directory(str(torch_lib))
    except Exception:
        pass
    importlib.invalidate_caches()


def engine_ready():
    if not RUNTIME_PY.exists():
        return False
    try:
        cmd=[RUNTIME_PY,'-c','import torch,diffusers,transformers,huggingface_hub,accelerate,safetensors; print("ENGINE_OK")']
        proc=subprocess.run([str(x) for x in cmd],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',timeout=30,**_hidden_subprocess_kwargs())
        if proc.returncode==0 and 'ENGINE_OK' in (proc.stdout or ''):
            activate_runtime_packages()
            return True
    except Exception:
        return False
    return False
'''
if old_ready not in s:
    raise SystemExit('engine_ready block not found')
s = s.replace(old_ready, new_ready, 1)

# pip는 --target을 쓰지 않고 전용 Python의 site-packages에 직접 설치합니다.
old_common = "            common=['--disable-pip-version-check','--no-warn-script-location','--upgrade','--target',str(PKG)]\n"
new_common = "            ENGINE_ROOT.mkdir(parents=True,exist_ok=True)\n            common=['--disable-pip-version-check','--no-warn-script-location','--upgrade']\n"
if old_common not in s:
    raise SystemExit('pip common args block not found')
s = s.replace(old_common, new_common, 1)

# 이전 --target 실패 흔적은 더 이상 엔진 상태와 무관하므로 정리만 합니다.
old_cleanup = "            if PKG.exists() and not engine_ready():\n                cb('이전 실패 설치 흔적을 정리합니다: '+str(PKG))\n                shutil.rmtree(PKG, ignore_errors=True)\n                PKG.mkdir(parents=True, exist_ok=True)\n"
new_cleanup = "            if PKG.exists():\n                cb('이전 --target 설치 흔적을 정리합니다: '+str(PKG))\n                shutil.rmtree(PKG, ignore_errors=True)\n                PKG.mkdir(parents=True, exist_ok=True)\n"
if old_cleanup in s:
    s = s.replace(old_cleanup, new_cleanup, 1)

# 두 번째 설치에서 Torch를 다른 버전으로 재설치하지 않도록 버전 범위와 --upgrade-strategy를 안정화합니다.
s = s.replace(
    "pip_install(common+['diffusers>=0.30','transformers>=4.44','accelerate>=0.33','safetensors>=0.4','huggingface_hub>=0.24','rembg>=2.0.57','onnxruntime>=1.18'],cb)",
    "pip_install(common+['--upgrade-strategy','only-if-needed','diffusers>=0.30,<0.41','transformers>=4.44,<5','accelerate>=0.33,<2','safetensors>=0.4,<1','huggingface_hub>=0.24,<1','rembg>=2.0.57,<3','onnxruntime>=1.18,<2'],cb)"
)

# 설치 완료 판정은 실제 import 테스트 후에만 성공 처리합니다.
old_done = "            log('AI 엔진 설치 완료')\n"
new_done = "            cb('AI 엔진 실제 import 검증을 시작합니다.')\n            _stream_process([RUNTIME_PY,'-c','import torch,diffusers,transformers,huggingface_hub,accelerate,safetensors; print(\"ENGINE_IMPORT_OK\"); print(\"torch=\"+torch.__version__); print(\"cuda=\"+str(torch.cuda.is_available()))'],cb,cwd=ENGINE_ROOT)\n            activate_runtime_packages()\n            log('AI 엔진 설치 및 import 검증 완료')\n"
if old_done not in s:
    raise SystemExit('install done marker not found')
s = s.replace(old_done, new_done, 1)

# AI 모듈을 GUI 프로세스에서 사용할 때 전용 site-packages를 활성화합니다.
s = s.replace("            from huggingface_hub import snapshot_download\n", "            activate_runtime_packages(); from huggingface_hub import snapshot_download\n", 1)
s = s.replace("        import torch; from diffusers import StableDiffusionXLPipeline\n", "        activate_runtime_packages(); import torch; from diffusers import StableDiffusionXLPipeline\n", 1)
s = s.replace("            self.after(0,lambda:self.stat('배경 제거 중...')); from rembg import remove;", "            self.after(0,lambda:self.stat('배경 제거 중...')); activate_runtime_packages(); from rembg import remove;", 1)

s = s.replace("APP='검은 성흔 Item AI v5'", "APP='검은 성흔 Item AI v6'", 1)

required = [
    "BlackStigmaItemAI_Engine",
    "RUNTIME_SITE=RUNTIME/'Lib'/'site-packages'",
    "common=['--disable-pip-version-check','--no-warn-script-location','--upgrade']",
    "ENGINE_IMPORT_OK",
    "PYTHONUTF8",
    "activate_runtime_packages()",
    "left_scroll=ttk.Scrollbar",
    "AI 엔진 설치 / 복구",
    "모델 다운로드 / 확인",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s:
        raise SystemExit('v6 marker missing: '+marker)
if "'--target',str(PKG)" in s:
    raise SystemExit('legacy --target install still present')

p.write_text(s,encoding='utf-8')
print('Item AI v6 patch applied')
