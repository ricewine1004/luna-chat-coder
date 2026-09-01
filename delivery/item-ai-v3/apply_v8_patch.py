from pathlib import Path
import subprocess
import sys

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

# v7까지 준비
if "APP='검은 성흔 Item AI v7'" not in s:
    subprocess.check_call([sys.executable, str(Path(__file__).with_name('apply_v7_patch.py'))])
    s = p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v7'" not in s:
    raise SystemExit('v7 source was not prepared')

# GUI 모델 상태 판정을 Hugging Face cache/snapshot 구조가 아닌 실제 local_dir 구조로 변경
old_model_ready = '''def model_ready(model_id, model_dir):
    root=Path(model_dir)
    if not root.exists(): return False
    needle=model_id.replace('/','--').lower()
    for p in root.rglob('*'):
        if p.is_dir() and needle in p.name.lower():
            try:
                if any(p.rglob('model_index.json')): return True
            except Exception: pass
    return False
'''
new_model_ready = '''def local_model_path(model_id, model_dir):
    safe=model_id.split('/')[-1].strip() or 'model'
    return Path(model_dir)/safe


def model_ready(model_id, model_dir):
    root=local_model_path(model_id, model_dir)
    if not root.exists(): return False
    required=[root/'model_index.json', root/'scheduler', root/'tokenizer', root/'unet', root/'vae']
    return required[0].is_file() and all(x.exists() for x in required[1:])
'''
if old_model_ready not in s:
    raise SystemExit('model_ready block not found')
s=s.replace(old_model_ready,new_model_ready,1)

# worker 다운로드를 cache_dir + snapshot symlink 방식에서 local_dir 실제 파일 방식으로 변경
old_download = '''def cmd_download(a):
    from huggingface_hub import snapshot_download
    p=snapshot_download(repo_id=a.model_id, cache_dir=a.model_dir)
    print('MODEL_READY='+str(p), flush=True)
'''
new_download = '''def model_local_path(model_id, model_dir):
    safe=model_id.split('/')[-1].strip() or 'model'
    return Path(model_dir)/safe


def cmd_download(a):
    from huggingface_hub import snapshot_download
    root=model_local_path(a.model_id,a.model_dir)
    root.mkdir(parents=True,exist_ok=True)
    os.environ['HF_HUB_DISABLE_SYMLINKS_WARNING']='1'
    os.environ['HF_HUB_DISABLE_XET']='1'
    print('MODEL_LOCAL_DIR='+str(root), flush=True)
    p=snapshot_download(
        repo_id=a.model_id,
        local_dir=str(root),
        local_dir_use_symlinks=False,
        resume_download=True,
        max_workers=4,
    )
    index=Path(p)/'model_index.json'
    if not index.is_file():
        raise RuntimeError('model_index.json 다운로드를 확인하지 못했습니다: '+str(index))
    required=['scheduler','tokenizer','unet','vae']
    missing=[name for name in required if not (Path(p)/name).exists()]
    if missing:
        raise RuntimeError('필수 모델 구성 누락: '+', '.join(missing))
    print('MODEL_READY='+str(Path(p).resolve()), flush=True)
'''
if old_download not in s:
    raise SystemExit('worker download block not found')
s=s.replace(old_download,new_download,1)

# 생성 시 반드시 local_dir 모델을 읽도록 변경
old_load = '''def load_pipe(a):
    import torch
    from diffusers import StableDiffusionXLPipeline
    dtype=torch.float16 if torch.cuda.is_available() else torch.float32
    pipe=StableDiffusionXLPipeline.from_pretrained(
        a.model_id,
        torch_dtype=dtype,
        cache_dir=a.model_dir,
        use_safetensors=True,
    )
'''
new_load = '''def load_pipe(a):
    import torch
    from diffusers import StableDiffusionXLPipeline
    dtype=torch.float16 if torch.cuda.is_available() else torch.float32
    model_path=model_local_path(a.model_id,a.model_dir)
    if not (model_path/'model_index.json').is_file():
        raise RuntimeError('로컬 모델이 준비되지 않았습니다: '+str(model_path))
    pipe=StableDiffusionXLPipeline.from_pretrained(
        str(model_path),
        torch_dtype=dtype,
        local_files_only=True,
        use_safetensors=True,
    )
'''
if old_load not in s:
    raise SystemExit('worker load_pipe block not found')
s=s.replace(old_load,new_load,1)

# worker 환경에서 symlink/Xet 경고 및 특수 저장 방식을 비활성화
old_env = "    env=os.environ.copy();env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8';env['PIP_DISABLE_PIP_VERSION_CHECK']='1'\n"
new_env = "    env=os.environ.copy();env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8';env['PIP_DISABLE_PIP_VERSION_CHECK']='1';env['HF_HUB_DISABLE_SYMLINKS_WARNING']='1';env['HF_HUB_DISABLE_XET']='1'\n"
if old_env not in s:
    raise SystemExit('worker env block not found')
s=s.replace(old_env,new_env,1)

# 모델 다운로드 완료 후 GUI 상태를 즉시 다시 판정할 수 있도록 로그 강화
old_log_ready = "            log('모델 준비 완료: '+result)\n"
new_log_ready = "            log('모델 준비 완료: '+result)\n            if not model_ready(self.cfg['model_id'],self.cfg['model_dir']): raise RuntimeError('모델 파일 검증에 실패했습니다.')\n"
if old_log_ready not in s:
    raise SystemExit('GUI model ready log not found')
s=s.replace(old_log_ready,new_log_ready,1)

s=s.replace("APP='검은 성흔 Item AI v7'","APP='검은 성흔 Item AI v8'",1)

required=[
    "APP='검은 성흔 Item AI v8'",
    "local_dir=str(root)",
    "local_dir_use_symlinks=False",
    "HF_HUB_DISABLE_SYMLINKS_WARNING",
    "HF_HUB_DISABLE_XET",
    "local_files_only=True",
    "model_local_path(a.model_id,a.model_dir)",
    "model_index.json",
    "left_scroll=ttk.Scrollbar",
    "AI 엔진 설치 / 복구",
    "모델 다운로드 / 확인",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s:
        raise SystemExit('v8 marker missing: '+marker)

# 이전 cache_dir 기반 모델 로딩/다운로드가 worker에 남지 않아야 함
if "snapshot_download(repo_id=a.model_id, cache_dir=a.model_dir)" in s:
    raise SystemExit('legacy snapshot cache download remains')
if "cache_dir=a.model_dir" in s:
    raise SystemExit('legacy cache_dir model load remains')

p.write_text(s,encoding='utf-8')
print('Item AI v8 patch applied')
