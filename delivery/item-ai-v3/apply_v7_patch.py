from pathlib import Path
import subprocess
import sys

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

# v6까지 먼저 적용합니다.
if "APP='검은 성흔 Item AI v6'" not in s:
    subprocess.check_call([sys.executable, str(Path(__file__).with_name('apply_v6_patch.py'))])
    s = p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v6'" not in s:
    raise SystemExit('v6 source was not prepared')

# AI 패키지는 PyInstaller GUI 프로세스에서 import하지 않습니다.
# 정상 Python 런타임에서 전용 worker를 실행하여 모델 다운로드/생성/배경 제거를 담당합니다.
insert_after = "GET_PIP_URL='https://bootstrap.pypa.io/get-pip.py'\n"
worker_defs = r'''WORKER=DATA/'ai_worker.py'

WORKER_CODE = r"""
import argparse, json, os, random, sys, time
from pathlib import Path


def cmd_download(a):
    from huggingface_hub import snapshot_download
    p=snapshot_download(repo_id=a.model_id, cache_dir=a.model_dir)
    print('MODEL_READY='+str(p), flush=True)


def load_pipe(a):
    import torch
    from diffusers import StableDiffusionXLPipeline
    dtype=torch.float16 if torch.cuda.is_available() else torch.float32
    pipe=StableDiffusionXLPipeline.from_pretrained(
        a.model_id,
        torch_dtype=dtype,
        cache_dir=a.model_dir,
        use_safetensors=True,
    )
    device='cuda' if torch.cuda.is_available() else 'cpu'
    pipe=pipe.to(device)
    if torch.cuda.is_available():
        pipe.enable_attention_slicing()
        try: pipe.enable_vae_slicing()
        except Exception: pass
    return pipe, torch, device


def cmd_generate(a):
    from PIL import Image
    pipe,torch,device=load_pipe(a)
    out=Path(a.output_dir); out.mkdir(parents=True,exist_ok=True)
    records=[]
    for i in range(a.count):
        seed=random.randint(1,2147483647)
        print(f'PROGRESS={i+1}/{a.count}', flush=True)
        gen=torch.Generator(device=device).manual_seed(seed)
        im=pipe(
            prompt=a.prompt,
            negative_prompt=a.negative,
            width=a.width,
            height=a.height,
            num_inference_steps=a.steps,
            guidance_scale=a.guidance,
            generator=gen,
        ).images[0]
        fn=out/f"{time.strftime('%Y%m%d_%H%M%S')}_{i+1}_{seed}.png"
        im.save(fn)
        records.append({'seed':seed,'path':str(fn),'subtype':a.subtype,'prompt':a.prompt})
    print('RESULT_JSON='+json.dumps(records,ensure_ascii=False), flush=True)
    del pipe
    if torch.cuda.is_available():
        try: torch.cuda.synchronize()
        except Exception: pass
        torch.cuda.empty_cache()
        try: torch.cuda.ipc_collect()
        except Exception: pass


def cmd_set(a):
    pipe,torch,device=load_pipe(a)
    out=Path(a.output_dir); out.mkdir(parents=True,exist_ok=True)
    items=json.loads(a.items_json)
    records=[]
    for i,item in enumerate(items):
        print(f'PROGRESS={i+1}/{len(items)} {item["subtype"]}', flush=True)
        seed=random.randint(1,2147483647)
        gen=torch.Generator(device=device).manual_seed(seed)
        im=pipe(
            prompt=item['prompt'], negative_prompt=item['negative'],
            width=a.width,height=a.height,num_inference_steps=a.steps,
            guidance_scale=a.guidance,generator=gen,
        ).images[0]
        fn=out/f"{i+1}_{item['subtype']}_{seed}.png"
        im.save(fn)
        records.append({'seed':seed,'path':str(fn),'subtype':item['subtype'],'prompt':item['prompt'],'set':a.set_name})
    print('RESULT_JSON='+json.dumps(records,ensure_ascii=False), flush=True)
    del pipe
    if torch.cuda.is_available():
        try: torch.cuda.synchronize()
        except Exception: pass
        torch.cuda.empty_cache()
        try: torch.cuda.ipc_collect()
        except Exception: pass


def cmd_remove(a):
    from PIL import Image
    from rembg import remove
    im=Image.open(a.input).convert('RGBA')
    out=remove(im)
    out.save(a.output)
    print('BG_READY='+a.output, flush=True)


def main():
    ap=argparse.ArgumentParser()
    sub=ap.add_subparsers(dest='cmd',required=True)
    d=sub.add_parser('download'); d.add_argument('--model-id',required=True); d.add_argument('--model-dir',required=True)
    g=sub.add_parser('generate')
    for q in (g,):
        q.add_argument('--model-id',required=True);q.add_argument('--model-dir',required=True);q.add_argument('--output-dir',required=True)
        q.add_argument('--prompt',required=True);q.add_argument('--negative',required=True);q.add_argument('--subtype',default='')
        q.add_argument('--width',type=int,default=768);q.add_argument('--height',type=int,default=768);q.add_argument('--steps',type=int,default=28);q.add_argument('--guidance',type=float,default=6.5);q.add_argument('--count',type=int,default=4)
    st=sub.add_parser('set');st.add_argument('--model-id',required=True);st.add_argument('--model-dir',required=True);st.add_argument('--output-dir',required=True);st.add_argument('--items-json',required=True);st.add_argument('--set-name',required=True);st.add_argument('--width',type=int,default=768);st.add_argument('--height',type=int,default=768);st.add_argument('--steps',type=int,default=28);st.add_argument('--guidance',type=float,default=6.5)
    r=sub.add_parser('remove');r.add_argument('--input',required=True);r.add_argument('--output',required=True)
    a=ap.parse_args()
    if a.cmd=='download':cmd_download(a)
    elif a.cmd=='generate':cmd_generate(a)
    elif a.cmd=='set':cmd_set(a)
    elif a.cmd=='remove':cmd_remove(a)

if __name__=='__main__':main()
"""


def ensure_worker():
    DATA.mkdir(parents=True,exist_ok=True)
    current = WORKER.read_text(encoding='utf-8') if WORKER.exists() else ''
    if current != WORKER_CODE:
        WORKER.write_text(WORKER_CODE,encoding='utf-8')


def run_worker(args, cb, result_prefix=None):
    ensure_runtime(cb)
    ensure_worker()
    cmd=[RUNTIME_PY,WORKER]+list(args)
    cb('AI 작업 프로세스 실행: '+str(args[0]))
    env=os.environ.copy();env['PYTHONUTF8']='1';env['PYTHONIOENCODING']='utf-8';env['PIP_DISABLE_PIP_VERSION_CHECK']='1'
    proc=subprocess.Popen([str(x) for x in cmd],cwd=str(DATA),env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace',**_hidden_subprocess_kwargs())
    result=None
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
'''
if insert_after not in s:
    raise SystemExit('worker insertion point not found')
s=s.replace(insert_after,insert_after+worker_defs,1)

# 모델 다운로드를 외부 정상 Python에서 수행합니다.
old_download = """        def work():\n            self.after(0,lambda:self.stat('모델 다운로드/확인 중...'))\n            activate_runtime_packages(); from huggingface_hub import snapshot_download\n            p=snapshot_download(repo_id=self.cfg['model_id'],cache_dir=self.cfg['model_dir']); log('모델 준비 완료: '+str(p))\n        self.bg(work)\n"""
new_download = """        def work():\n            self.after(0,lambda:self.stat('모델 다운로드/확인 중...'))\n            result=run_worker(['download','--model-id',self.cfg['model_id'],'--model-dir',self.cfg['model_dir']],lambda x:log(x),'MODEL_READY=')\n            if not result: raise RuntimeError('모델 다운로드 완료 경로를 확인하지 못했습니다.')\n            log('모델 준비 완료: '+result)\n        self.bg(work)\n"""
if old_download not in s:
    raise SystemExit('download_model block not found')
s=s.replace(old_download,new_download,1)

# 파이프라인을 GUI 프로세스 안에 로드하지 않습니다.
start=s.index('    def load_pipe(self):')
end=s.index('\n    def generate(self):',start)
s=s[:start]+"    def load_pipe(self):\n        raise RuntimeError('v7에서는 AI 모델을 GUI 프로세스에 직접 로드하지 않습니다.')\n"+s[end:]

# 일반 생성은 worker 한 프로세스에서 4장을 생성합니다.
start=s.index('    def generate(self):')
end=s.index('\n    def generate_set(self):',start)
new_generate = r'''    def generate(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        p,n=self.prompt(); sub=self.v['sub'].get(); self.write('생성 시작: '+sub)
        def work():
            out=Path(self.cfg['output_dir'])/'originals';out.mkdir(parents=True,exist_ok=True)
            raw=run_worker(['generate','--model-id',self.cfg['model_id'],'--model-dir',self.cfg['model_dir'],'--output-dir',str(out),'--prompt',p,'--negative',n,'--subtype',sub,'--width',str(int(self.cfg['width'])),'--height',str(int(self.cfg['height'])),'--steps',str(int(self.cfg['steps'])),'--guidance',str(float(self.cfg['guidance'])),'--count','4'],lambda x:log(x),'RESULT_JSON=')
            if not raw: raise RuntimeError('이미지 생성 결과를 받지 못했습니다.')
            records=json.loads(raw); arr=[]
            for rec in records:
                arr.append(Image.open(rec['path']).copy())
            if not self.closing:
                self.images=arr;self.meta=records;self.after(0,self.refresh_all);log('4장 생성 완료')
        self.bg(work)
'''
s=s[:start]+new_generate+s[end:]

# 세트 생성도 worker에서 수행합니다.
start=s.index('    def generate_set(self):')
end=s.index('\n    def select(self,i):',start)
new_set = r'''    def generate_set(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        types=['한손검','투구','갑옷','반지'];name=self.setname.get().strip() or '이름 없는 세트'
        items=[]
        for sub in types:
            p,n=self.prompt(sub);items.append({'subtype':sub,'prompt':p,'negative':n})
        def work():
            out=Path(self.cfg['output_dir'])/'sets'/time.strftime('%Y%m%d_%H%M%S');out.mkdir(parents=True,exist_ok=True)
            raw=run_worker(['set','--model-id',self.cfg['model_id'],'--model-dir',self.cfg['model_dir'],'--output-dir',str(out),'--items-json',json.dumps(items,ensure_ascii=False),'--set-name',name,'--width',str(int(self.cfg['width'])),'--height',str(int(self.cfg['height'])),'--steps',str(int(self.cfg['steps'])),'--guidance',str(float(self.cfg['guidance']))],lambda x:log(x),'RESULT_JSON=')
            if not raw: raise RuntimeError('세트 생성 결과를 받지 못했습니다.')
            records=json.loads(raw);arr=[Image.open(x['path']).copy() for x in records]
            if not self.closing:
                self.images=arr;self.meta=records;self.after(0,self.refresh_all);log('장비 세트 생성 완료')
        self.bg(work)
'''
s=s[:start]+new_set+s[end:]

# 배경 제거도 worker에서 수행합니다.
start=s.index('    def remove_bg(self):')
end=s.index('\n    def export_icon(self):',start)
new_bg = r'''    def remove_bg(self):
        if self.images[self.sel] is None:return messagebox.showwarning('안내','먼저 이미지를 생성해 주세요.')
        def work():
            self.after(0,lambda:self.stat('배경 제거 중...'))
            src=Path((self.meta[self.sel] or {}).get('path',''))
            if not src.exists():
                tmp=Path(self.cfg['output_dir'])/'temp';tmp.mkdir(parents=True,exist_ok=True);src=tmp/'selected.png';self.images[self.sel].save(src)
            out=Path(self.cfg['output_dir'])/'transparent';out.mkdir(parents=True,exist_ok=True);fn=out/f"transparent_{time.strftime('%Y%m%d_%H%M%S')}.png"
            result=run_worker(['remove','--input',str(src),'--output',str(fn)],lambda x:log(x),'BG_READY=')
            if not result: raise RuntimeError('배경 제거 결과를 받지 못했습니다.')
            self.images[self.sel]=Image.open(fn).copy()
            if not self.closing:self.after(0,lambda:self.redraw(self.sel));log('배경 제거 완료: '+str(fn))
        self.bg(work)
'''
s=s[:start]+new_bg+s[end:]

# GUI에서 torch를 import하지 않고 외부 작업 프로세스 종료가 GPU 정리를 담당합니다.
start=s.index('    def unload(self):')
end=s.index('\n    def settings(self):',start)
new_unload = r'''    def unload(self):
        self.pipe=None;self.pipe_id=None;gc.collect()
        log('AI 작업은 외부 프로세스 방식이며 현재 GUI에 상주한 GPU 모델이 없습니다.')
        if not self.closing:self.write('AI/GPU 작업 프로세스 정리 상태 확인 완료')
'''
s=s[:start]+new_unload+s[end:]

s=s.replace("APP='검은 성흔 Item AI v6'","APP='검은 성흔 Item AI v7'",1)

required=['WORKER_CODE','run_worker(','MODEL_READY=','RESULT_JSON=','BG_READY=',"APP='검은 성흔 Item AI v7'",'left_scroll=ttk.Scrollbar','AI 엔진 설치 / 복구','모델 다운로드 / 확인','WM_DELETE_WINDOW']
for marker in required:
    if marker not in s: raise SystemExit('v7 marker missing: '+marker)
# GUI 프로세스에서 AI 패키지 직접 import가 남지 않았는지 핵심 구문 검사
for bad in ['activate_runtime_packages(); from huggingface_hub import snapshot_download','activate_runtime_packages(); import torch; from diffusers import StableDiffusionXLPipeline','activate_runtime_packages(); from rembg import remove']:
    if bad in s: raise SystemExit('legacy GUI AI import remains: '+bad)

p.write_text(s,encoding='utf-8')
print('Item AI v7 patch applied')
