import os, sys, json, time, random, threading, traceback, importlib.util, gc, shutil, subprocess
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
from PIL import Image, ImageTk

APP='검은 성흔 Item AI v3'
FROZEN=getattr(sys,'frozen',False)
BASE=Path(sys.executable).resolve().parent if FROZEN else Path(__file__).resolve().parent
DATA=BASE/'BlackStigmaItemAI_Data'; DATA.mkdir(exist_ok=True)
PKG=DATA/'engine_packages'; PKG.mkdir(exist_ok=True)
MODELS=DATA/'models'; MODELS.mkdir(exist_ok=True)
OUTPUTS=DATA/'outputs'; OUTPUTS.mkdir(exist_ok=True)
LOGS=DATA/'logs'; LOGS.mkdir(exist_ok=True)
LOG=LOGS/'app.log'; CFG=DATA/'config.json'
if str(PKG) not in sys.path: sys.path.insert(0,str(PKG))

CATEGORIES={'무기':['한손검','양손검','단검','도끼','철퇴','창','활','석궁','지팡이','완드'],'방어구':['투구','갑옷','장갑','장화','방패','망토'],'악세사리':['반지','목걸이','귀걸이','허리띠','부적'],'물약':['생명력 물약','마나 물약','해독 물약','강화 물약','저항 물약'],'커런시':['성흔석','핏빛 파편','균열 핵','봉인 룬','타락 주화'],'재료':['뼈','가죽','광석','수정','룬 조각','정수','몬스터 핵'],'잡템':['낡은 열쇠','부서진 가면','찢어진 주문서','고대 동전','유물 조각']}
RARITIES=['일반','마법','희귀','고유','전설']; MATERIALS=['자동','철','강철','흑철','은','금','뼈','가죽','수정','흑요석','목재']; ELEMENTS=['없음','화염','냉기','번개','독','피','암흑','성스러움','공허']; MOODS=['검은 성흔 기본','타락','고대','악마','성스러움','망자','왕국 유물','심연','핏빛']
KO={'한손검':'one-handed sword','양손검':'two-handed greatsword','단검':'dagger','도끼':'axe','철퇴':'mace','창':'spear','활':'bow','석궁':'crossbow','지팡이':'staff','완드':'wand','투구':'helmet','갑옷':'body armor','장갑':'gauntlets','장화':'boots','방패':'shield','망토':'cloak','반지':'ring','목걸이':'amulet','귀걸이':'earring','허리띠':'belt','부적':'talisman','생명력 물약':'health potion','마나 물약':'mana potion','해독 물약':'antidote potion','강화 물약':'enhancement elixir','저항 물약':'resistance potion','성흔석':'dark stigma stone','핏빛 파편':'blood shard','균열 핵':'rift core','봉인 룬':'sealed rune','타락 주화':'corrupted coin','뼈':'monster bone','가죽':'hide','광석':'ore','수정':'crystal','룬 조각':'rune fragment','정수':'essence','몬스터 핵':'monster core','낡은 열쇠':'ancient worn key','부서진 가면':'broken mask','찢어진 주문서':'torn scroll','고대 동전':'ancient coin','유물 조각':'relic fragment','일반':'common','마법':'magic','희귀':'rare','고유':'unique','전설':'legendary','철':'iron','강철':'steel','흑철':'black iron','은':'silver','금':'gold','가죽':'leather','수정':'crystal','흑요석':'obsidian','목재':'wood','화염':'fire','냉기':'frost','번개':'lightning','독':'poison','피':'blood','암흑':'darkness','성스러움':'holy','공허':'void','타락':'corrupted','고대':'ancient','악마':'demonic','망자':'undead','왕국 유물':'fallen kingdom relic','심연':'abyssal','핏빛':'blood-soaked'}
RS={'일반':'practical simple design, minimal ornamentation','마법':'faint magical glow, subtle runes','희귀':'ornate details, premium craftsmanship','고유':'signature motif, memorable silhouette','전설':'epic artifact, iconic silhouette, elaborate ornamentation'}

def log(s):
    line=f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {s}\n"
    try:
        with LOG.open('a',encoding='utf-8') as f:f.write(line)
    except Exception: pass
    return line

def installed(name):
    importlib.invalidate_caches(); return importlib.util.find_spec(name) is not None

def engine_ready(): return all(installed(x) for x in ['torch','diffusers','transformers','huggingface_hub','accelerate','safetensors'])

def model_ready(model_id, model_dir):
    root=Path(model_dir)
    if not root.exists(): return False
    needle=model_id.replace('/','--').lower()
    for p in root.rglob('*'):
        if p.is_dir() and needle in p.name.lower():
            try:
                if any(p.rglob('model_index.json')): return True
            except Exception: pass
    return False

def pip_install(args, cb):
    from pip._internal.cli.main import main as pipmain
    cb('pip ' + ' '.join(args)); rc=pipmain(args)
    if rc: raise RuntimeError(f'패키지 설치 실패 (코드 {rc})')
    importlib.invalidate_caches()

class App(tk.Tk):
    def __init__(self):
        super().__init__(); self.title(APP); self.geometry('1380x900'); self.minsize(1180,780)
        self.protocol('WM_DELETE_WINDOW', self.on_close)
        self.closing=False; self.busy=False; self.pipe=None; self.pipe_id=None; self.images=[None]*4; self.meta=[None]*4; self.refs=[None]*4; self.sel=0
        self.cfg={'model_dir':str(MODELS),'output_dir':str(OUTPUTS),'model_id':'stabilityai/stable-diffusion-xl-base-1.0','width':768,'height':768,'steps':28,'guidance':6.5}
        if CFG.exists():
            try:self.cfg.update(json.loads(CFG.read_text(encoding='utf-8')))
            except Exception: pass
        self.style_ui(); self.ui(); self.refresh_engine(); self.write('프로그램 시작')

    def style_ui(self):
        self.configure(bg='#17191f'); s=ttk.Style(self); s.theme_use('clam')
        s.configure('TFrame',background='#17191f'); s.configure('Panel.TFrame',background='#20232b'); s.configure('Ready.TFrame',background='#252a36')
        s.configure('TLabel',background='#17191f',foreground='#e7e7e7'); s.configure('Panel.TLabel',background='#20232b',foreground='#e7e7e7'); s.configure('Ready.TLabel',background='#252a36',foreground='#eef3ff')
        s.configure('Title.TLabel',background='#17191f',foreground='white',font=('Malgun Gothic',18,'bold')); s.configure('Head.TLabel',background='#252a36',foreground='white',font=('Malgun Gothic',12,'bold'))
        s.configure('TButton',font=('Malgun Gothic',10,'bold'),padding=8); s.configure('Ready.TButton',font=('Malgun Gothic',11,'bold'),padding=11); s.configure('TCombobox',padding=5)

    def ui(self):
        root=ttk.Frame(self,padding=12); root.pack(fill='both',expand=True)
        ttk.Label(root,text=APP,style='Title.TLabel').pack(anchor='w'); ttk.Label(root,text='검은 성흔(가칭) 전용 로컬 아이템 이미지 생성기').pack(anchor='w',pady=(0,10))
        body=ttk.Frame(root); body.pack(fill='both',expand=True)
        left=ttk.Frame(body,style='Panel.TFrame',padding=14,width=390); left.pack(side='left',fill='y'); left.pack_propagate(False)
        right=ttk.Frame(body,padding=(14,0,0,0)); right.pack(side='left',fill='both',expand=True)

        ready=ttk.Frame(left,style='Ready.TFrame',padding=12); ready.pack(fill='x',pady=(0,12))
        ttk.Label(ready,text='AI 준비',style='Head.TLabel').pack(anchor='w')
        self.engine_state=ttk.Label(ready,text='엔진 상태 확인 중...',style='Ready.TLabel'); self.engine_state.pack(anchor='w',pady=(6,2))
        self.model_state=ttk.Label(ready,text='모델 상태 확인 중...',style='Ready.TLabel'); self.model_state.pack(anchor='w',pady=(0,8))
        self.btn_engine=ttk.Button(ready,text='1. AI 엔진 설치 / 복구',style='Ready.TButton',command=self.install_engine); self.btn_engine.pack(fill='x',pady=4)
        self.btn_model=ttk.Button(ready,text='2. 모델 다운로드 / 확인',style='Ready.TButton',command=self.download_model); self.btn_model.pack(fill='x',pady=4)
        ttk.Label(ready,text='처음 실행 시 위 1 → 2 순서로 진행해 주세요.',style='Ready.TLabel',wraplength=340).pack(anchor='w',pady=(6,0))

        self.v={}; self.c={}
        self.combo(left,'카테고리','cat',list(CATEGORIES),self.cat_change); self.combo(left,'세부 종류','sub',CATEGORIES['무기']); self.combo(left,'등급','rar',RARITIES); self.combo(left,'재질','mat',MATERIALS); self.combo(left,'속성','elm',ELEMENTS); self.combo(left,'분위기','mood',MOODS)
        ttk.Label(left,text='세트 이름',style='Panel.TLabel').pack(anchor='w',pady=(8,3)); self.setname=tk.StringVar(); ttk.Entry(left,textvariable=self.setname).pack(fill='x')
        ttk.Label(left,text='추가 설명',style='Panel.TLabel').pack(anchor='w',pady=(8,3)); self.desc=tk.Text(left,height=3,bg='#15171c',fg='white',insertbackground='white',relief='flat'); self.desc.pack(fill='x')
        for txt,cmd in [('아이템 4장 생성',self.generate),('변형 4장 생성',self.generate),('장비 세트 4종 생성',self.generate_set),('선택 이미지 배경 제거',self.remove_bg),('게임 아이콘 저장',self.export_icon),('GPU 메모리 해제',self.unload),('설정',self.settings)]: ttk.Button(left,text=txt,command=cmd).pack(fill='x',pady=3)

        gallery=ttk.Frame(right); gallery.pack(fill='both',expand=True); self.cv=[]
        for r in range(2):
            gallery.rowconfigure(r,weight=1); gallery.columnconfigure(r,weight=1)
            for c in range(2):
                i=r*2+c; f=tk.Frame(gallery,bg='#242832',highlightthickness=2,highlightbackground='#303641'); f.grid(row=r,column=c,sticky='nsew',padx=6,pady=6)
                cv=tk.Canvas(f,bg='#242832',highlightthickness=0); cv.pack(fill='both',expand=True); cv.bind('<Button-1>',lambda e,n=i:self.select(n)); cv.bind('<Configure>',lambda e,n=i:self.redraw(n)); self.cv.append(cv)
        self.status=ttk.Label(right,text='준비됨'); self.status.pack(anchor='w')
        self.logbox=tk.Text(right,height=8,bg='#111318',fg='#cfd3dc',relief='flat'); self.logbox.pack(fill='x',pady=(5,0)); self.logbox.config(state='disabled')

    def combo(self,p,l,k,vals,cb=None):
        ttk.Label(p,text=l,style='Panel.TLabel').pack(anchor='w',pady=(5,2)); v=tk.StringVar(value=vals[0]); self.v[k]=v
        c=ttk.Combobox(p,textvariable=v,values=vals,state='readonly'); c.pack(fill='x'); self.c[k]=c
        if cb:c.bind('<<ComboboxSelected>>',cb)
    def cat_change(self,e=None): vals=CATEGORIES[self.v['cat'].get()]; self.c['sub']['values']=vals; self.v['sub'].set(vals[0])
    def write(self,s):
        line=log(s)
        if self.closing:return
        self.logbox.config(state='normal'); self.logbox.insert('end',line); self.logbox.see('end'); self.logbox.config(state='disabled')
    def stat(self,s):
        if not self.closing:self.status.config(text=s); self.update_idletasks()
    def refresh_engine(self):
        if self.closing:return
        er=engine_ready(); mr=model_ready(self.cfg['model_id'],self.cfg['model_dir']) if er else False
        self.engine_state.config(text='엔진 상태: 설치됨' if er else '엔진 상태: 설치 필요')
        self.model_state.config(text='모델 상태: 준비됨' if mr else '모델 상태: 다운로드 필요')
        self.btn_model.config(state='normal' if er else 'disabled')
    def bg(self,fn):
        if self.closing:return
        if self.busy:return messagebox.showinfo('안내','현재 작업이 진행 중입니다.')
        self.busy=True
        def run():
            try:fn()
            except Exception as e:
                tb=traceback.format_exc(); log('오류: '+str(e)); log(tb)
                if not self.closing:self.after(0,lambda:messagebox.showerror('오류',f'{e}\n\n로그: {LOG}'))
            finally:
                self.busy=False
                if not self.closing:self.after(0,lambda:self.stat('준비됨')); self.after(0,self.refresh_engine)
        threading.Thread(target=run,daemon=True,name='BlackStigmaWorker').start()

    def install_engine(self):
        def work():
            self.after(0,lambda:self.stat('AI 엔진 설치 중...')); cb=lambda s: log(s)
            common=['--disable-pip-version-check','--no-warn-script-location','--upgrade','--target',str(PKG)]
            if shutil.which('nvidia-smi'):
                try:pip_install(common+['--index-url','https://download.pytorch.org/whl/cu128','torch','torchvision'],cb)
                except Exception: pip_install(common+['torch','torchvision'],cb)
            else:pip_install(common+['torch','torchvision'],cb)
            pip_install(common+['diffusers>=0.30','transformers>=4.44','accelerate>=0.33','safetensors>=0.4','huggingface_hub>=0.24','rembg>=2.0.57','onnxruntime>=1.18'],cb)
            log('AI 엔진 설치 완료')
        self.bg(work)

    def download_model(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        def work():
            self.after(0,lambda:self.stat('모델 다운로드/확인 중...'))
            from huggingface_hub import snapshot_download
            p=snapshot_download(repo_id=self.cfg['model_id'],cache_dir=self.cfg['model_dir']); log('모델 준비 완료: '+str(p))
        self.bg(work)

    def prompt(self,sub=None):
        sub=sub or self.v['sub'].get(); parts=['dark fantasy action RPG inventory item icon',KO.get(sub,sub),KO.get(self.v['rar'].get(),self.v['rar'].get()),RS[self.v['rar'].get()],'grim gothic Korean dark fantasy aesthetic','single isolated item','centered composition','clean readable silhouette','highly detailed game asset','studio lighting','plain neutral background','no character','no hands','no text','no letters','no watermark']
        if self.v['mat'].get()!='자동':parts.append('made of '+KO.get(self.v['mat'].get(),self.v['mat'].get()))
        if self.v['elm'].get()!='없음':parts.append('imbued with '+KO.get(self.v['elm'].get(),self.v['elm'].get())+' energy')
        if self.v['mood'].get()!='검은 성흔 기본':parts.append(KO.get(self.v['mood'].get(),self.v['mood'].get()))
        if self.setname.get().strip():parts+=['belongs to the '+self.setname.get().strip()+' item set','shared motif language across the set']
        extra=self.desc.get('1.0','end').strip(); parts += [extra] if extra else []
        return ', '.join(parts),'person, human, character, hand, holding item, multiple items, scenery, landscape, text, letters, logo, watermark, frame, UI, low quality, blurry, cropped'

    def load_pipe(self):
        if self.pipe is not None and self.pipe_id==self.cfg['model_id']:return self.pipe
        import torch; from diffusers import StableDiffusionXLPipeline
        dt=torch.float16 if torch.cuda.is_available() else torch.float32
        self.pipe=StableDiffusionXLPipeline.from_pretrained(self.cfg['model_id'],torch_dtype=dt,cache_dir=self.cfg['model_dir'],use_safetensors=True)
        device='cuda' if torch.cuda.is_available() else 'cpu'; self.pipe=self.pipe.to(device)
        if torch.cuda.is_available():
            self.pipe.enable_attention_slicing()
            try:self.pipe.enable_vae_slicing()
            except Exception:pass
        self.pipe_id=self.cfg['model_id']; return self.pipe

    def generate(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        p,n=self.prompt(); sub=self.v['sub'].get(); self.write('생성 시작: '+sub)
        def work():
            import torch; pipe=self.load_pipe(); arr=[]; meta=[]; out=Path(self.cfg['output_dir'])/'originals'; out.mkdir(parents=True,exist_ok=True)
            for i in range(4):
                if self.closing:return
                self.after(0,lambda x=i:self.stat(f'이미지 생성 중... {x+1}/4')); seed=random.randint(1,2147483647); device='cuda' if torch.cuda.is_available() else 'cpu'; g=torch.Generator(device=device).manual_seed(seed)
                im=pipe(prompt=p,negative_prompt=n,width=int(self.cfg['width']),height=int(self.cfg['height']),num_inference_steps=int(self.cfg['steps']),guidance_scale=float(self.cfg['guidance']),generator=g).images[0]
                fn=out/f"{time.strftime('%Y%m%d_%H%M%S')}_{i+1}_{seed}.png"; im.save(fn); arr.append(im.copy()); meta.append({'seed':seed,'prompt':p,'path':str(fn),'subtype':sub})
            if not self.closing:self.images=arr; self.meta=meta; self.after(0,self.refresh_all); log('4장 생성 완료')
        self.bg(work)

    def generate_set(self):
        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')
        types=['한손검','투구','갑옷','반지']; name=self.setname.get().strip() or '이름 없는 세트'
        def work():
            import torch; pipe=self.load_pipe(); arr=[]; meta=[]; out=Path(self.cfg['output_dir'])/'sets'/time.strftime('%Y%m%d_%H%M%S'); out.mkdir(parents=True,exist_ok=True)
            for i,sub in enumerate(types):
                if self.closing:return
                self.after(0,lambda x=i,s=sub:self.stat(f'세트 생성 중... {x+1}/4 {s}')); p,n=self.prompt(sub); seed=random.randint(1,2147483647); device='cuda' if torch.cuda.is_available() else 'cpu'; g=torch.Generator(device=device).manual_seed(seed)
                im=pipe(prompt=p,negative_prompt=n,width=int(self.cfg['width']),height=int(self.cfg['height']),num_inference_steps=int(self.cfg['steps']),guidance_scale=float(self.cfg['guidance']),generator=g).images[0]
                fn=out/f'{i+1}_{sub}_{seed}.png'; im.save(fn); arr.append(im.copy()); meta.append({'seed':seed,'prompt':p,'path':str(fn),'subtype':sub,'set':name})
            if not self.closing:self.images=arr; self.meta=meta; self.after(0,self.refresh_all); log('장비 세트 생성 완료')
        self.bg(work)

    def select(self,i):self.sel=i;[c.master.configure(highlightbackground='#7f9cff' if n==i else '#303641') for n,c in enumerate(self.cv)]
    def redraw(self,i):
        if self.closing:return
        c=self.cv[i]; c.delete('all'); im=self.images[i]
        if im is None:return c.create_text(max(20,c.winfo_width()/2),max(20,c.winfo_height()/2),text=f'결과 {i+1}',fill='#8a909c',font=('Malgun Gothic',14))
        x=im.copy(); x.thumbnail((max(50,c.winfo_width()-20),max(50,c.winfo_height()-20)),Image.Resampling.LANCZOS); r=ImageTk.PhotoImage(x); self.refs[i]=r; c.create_image(c.winfo_width()/2,c.winfo_height()/2,image=r); c.create_text(10,10,anchor='nw',text=(self.meta[i] or {}).get('subtype',''),fill='#dce6ff',font=('Malgun Gothic',11,'bold'))
    def refresh_all(self):[self.redraw(i) for i in range(4)];self.select(self.sel)

    def remove_bg(self):
        if self.images[self.sel] is None:return messagebox.showwarning('안내','먼저 이미지를 생성해 주세요.')
        def work():
            self.after(0,lambda:self.stat('배경 제거 중...')); from rembg import remove; im=remove(self.images[self.sel].convert('RGBA')); self.images[self.sel]=im
            out=Path(self.cfg['output_dir'])/'transparent'; out.mkdir(parents=True,exist_ok=True); fn=out/f"transparent_{time.strftime('%Y%m%d_%H%M%S')}.png"; im.save(fn)
            if not self.closing:self.after(0,lambda:self.redraw(self.sel)); log('배경 제거 완료: '+str(fn))
        self.bg(work)

    def export_icon(self):
        im=self.images[self.sel]
        if im is None:return messagebox.showwarning('안내','먼저 이미지를 생성해 주세요.')
        out=Path(self.cfg['output_dir'])/'icons'/time.strftime('%Y%m%d_%H%M%S'); out.mkdir(parents=True,exist_ok=True); rgba=im.convert('RGBA'); bbox=rgba.getbbox(); crop=rgba.crop(bbox) if bbox else rgba; side=max(crop.width,crop.height); margin=max(4,int(side*.08)); sq=Image.new('RGBA',(side+2*margin,side+2*margin),(0,0,0,0)); sq.alpha_composite(crop,((sq.width-crop.width)//2,(sq.height-crop.height)//2))
        for sz in (1024,512,256,128,64):sq.resize((sz,sz),Image.Resampling.LANCZOS).save(out/f'item_{sz}.png')
        (out/'metadata.json').write_text(json.dumps(self.meta[self.sel] or {},ensure_ascii=False,indent=2),encoding='utf-8'); log('아이콘 저장: '+str(out)); messagebox.showinfo('완료','아이콘 저장 완료\n'+str(out))

    def unload(self):
        self.pipe=None; self.pipe_id=None; gc.collect()
        try:
            import torch
            if torch.cuda.is_available():
                try:torch.cuda.synchronize()
                except Exception:pass
                torch.cuda.empty_cache()
                try:torch.cuda.ipc_collect()
                except Exception:pass
        except Exception:pass
        log('GPU/AI 메모리 해제 완료')
        if not self.closing:self.write('GPU/AI 메모리 해제 완료')

    def settings(self):
        w=tk.Toplevel(self); w.title('설정'); w.geometry('650x350'); f=ttk.Frame(w,padding=15); f.pack(fill='both',expand=True)
        md=tk.StringVar(value=self.cfg['model_dir']); od=tk.StringVar(value=self.cfg['output_dir']); mi=tk.StringVar(value=self.cfg['model_id'])
        def row(label,var,browse=False):
            ttk.Label(f,text=label).pack(anchor='w',pady=(7,2)); z=ttk.Frame(f); z.pack(fill='x'); ttk.Entry(z,textvariable=var).pack(side='left',fill='x',expand=True)
            if browse:ttk.Button(z,text='찾아보기',command=lambda:var.set(filedialog.askdirectory(initialdir=var.get()) or var.get())).pack(side='left',padx=(5,0))
        row('AI 모델 저장 경로',md,True); row('생성 이미지 저장 경로',od,True); row('모델 ID',mi)
        def save():
            self.cfg.update(model_dir=md.get(),output_dir=od.get(),model_id=mi.get()); Path(md.get()).mkdir(parents=True,exist_ok=True); Path(od.get()).mkdir(parents=True,exist_ok=True); CFG.write_text(json.dumps(self.cfg,ensure_ascii=False,indent=2),encoding='utf-8'); w.destroy(); self.refresh_engine()
        ttk.Button(f,text='저장',command=save).pack(anchor='e',pady=15)

    def on_close(self):
        if self.closing:return
        self.closing=True; log('종료 시작 - 새 작업 차단')
        try:self.status.config(text='종료 정리 중...'); self.update_idletasks()
        except Exception:pass
        try:self.unload()
        except Exception as e:log('종료 메모리 정리 오류: '+str(e))
        try:
            self.images=[None]*4; self.meta=[None]*4; self.refs=[None]*4; gc.collect(); log('이미지/GUI 참조 해제 완료')
        except Exception:pass
        try:self.quit(); self.destroy()
        finally:
            log('프로그램 종료 완료')

if __name__=='__main__':
    try:App().mainloop()
    except Exception:
        log(traceback.format_exc())
        try:messagebox.showerror('치명적 오류','프로그램 실행 오류\n'+str(LOG))
        except Exception:pass
