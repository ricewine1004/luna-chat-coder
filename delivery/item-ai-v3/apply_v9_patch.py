from pathlib import Path
import subprocess
import sys

p = Path(__file__).with_name('item_ai_app.py')
s = p.read_text(encoding='utf-8')

# v8까지 준비
if "APP='검은 성흔 Item AI v8'" not in s:
    subprocess.check_call([sys.executable, str(Path(__file__).with_name('apply_v8_patch.py'))])
    s = p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v8'" not in s:
    raise SystemExit('v8 source was not prepared')

# 품질을 크게 낮추지 않으면서 SDXL Base보다 가벼운 SSD-1B를 빠른 기본 모델로 사용합니다.
insert_after = "RARITIES=['일반','마법','희귀','고유','전설']; MATERIALS=['자동','철','강철','흑철','은','금','뼈','가죽','수정','흑요석','목재']; ELEMENTS=['없음','화염','냉기','번개','독','피','암흑','성스러움','공허']; MOODS=['검은 성흔 기본','타락','고대','악마','성스러움','망자','왕국 유물','심연','핏빛']\n"
profiles = """MODEL_PROFILES={
    '빠른 모델 (SSD-1B)':{
        'id':'segmind/SSD-1B','steps':22,'guidance':7.0,'width':768,'height':768,
        'desc':'SDXL 계열 경량화 모델 - 빠른 테스트/초안용'
    },
    '고품질 모델 (SDXL Base)':{
        'id':'stabilityai/stable-diffusion-xl-base-1.0','steps':28,'guidance':6.5,'width':768,'height':768,
        'desc':'SDXL Base - 최종 품질/디테일 우선'
    },
}
DEFAULT_PROFILE='빠른 모델 (SSD-1B)'
"""
if insert_after not in s:
    raise SystemExit('profile insertion point not found')
s=s.replace(insert_after,insert_after+profiles,1)

# 기본 설정을 빠른 모델로 변경하고 현재 프로필을 저장합니다.
old_cfg = "self.cfg={'model_dir':str(MODELS),'output_dir':str(OUTPUTS),'model_id':'stabilityai/stable-diffusion-xl-base-1.0','width':768,'height':768,'steps':28,'guidance':6.5}"
new_cfg = "self.cfg={'model_dir':str(MODELS),'output_dir':str(OUTPUTS),'model_profile':DEFAULT_PROFILE,'model_id':MODEL_PROFILES[DEFAULT_PROFILE]['id'],'width':MODEL_PROFILES[DEFAULT_PROFILE]['width'],'height':MODEL_PROFILES[DEFAULT_PROFILE]['height'],'steps':MODEL_PROFILES[DEFAULT_PROFILE]['steps'],'guidance':MODEL_PROFILES[DEFAULT_PROFILE]['guidance']}"
if old_cfg not in s:
    raise SystemExit('default cfg block not found')
s=s.replace(old_cfg,new_cfg,1)

# 오래된 config에 프로필 정보가 없으면 model_id를 보고 복구합니다.
old_after_cfg = "        self.style_ui(); self.ui(); self.refresh_engine(); self.write('프로그램 시작')\n"
new_after_cfg = """        if self.cfg.get('model_profile') not in MODEL_PROFILES:
            found=next((name for name,info in MODEL_PROFILES.items() if info['id']==self.cfg.get('model_id')),DEFAULT_PROFILE)
            self.cfg['model_profile']=found
        self._apply_profile_values(self.cfg['model_profile'], save=False)
        self.style_ui(); self.ui(); self.refresh_engine(); self.write('프로그램 시작')
"""
if old_after_cfg not in s:
    raise SystemExit('post config init point not found')
s=s.replace(old_after_cfg,new_after_cfg,1)

# App 내부에 모델 프로필 적용/저장 메서드를 추가합니다.
insert_method_at = "    def style_ui(self):\n"
profile_methods = '''    def _save_cfg(self):
        try:
            Path(self.cfg['model_dir']).mkdir(parents=True,exist_ok=True)
            Path(self.cfg['output_dir']).mkdir(parents=True,exist_ok=True)
            CFG.write_text(json.dumps(self.cfg,ensure_ascii=False,indent=2),encoding='utf-8')
        except Exception as e:
            log('설정 저장 경고: '+str(e))

    def _apply_profile_values(self,name,save=True):
        if name not in MODEL_PROFILES:name=DEFAULT_PROFILE
        info=MODEL_PROFILES[name]
        self.cfg['model_profile']=name
        self.cfg['model_id']=info['id']
        self.cfg['steps']=info['steps']
        self.cfg['guidance']=info['guidance']
        self.cfg['width']=info['width']
        self.cfg['height']=info['height']
        if save:self._save_cfg()

    def profile_change(self,e=None):
        name=self.model_profile_var.get()
        self._apply_profile_values(name,save=True)
        self.write('현재 생성 모델 변경: '+name+' / '+MODEL_PROFILES[name]['id'])
        self.refresh_engine()

    def download_profile(self,name):
        if name not in MODEL_PROFILES:return
        self.model_profile_var.set(name)
        self._apply_profile_values(name,save=True)
        self.download_model()

'''
if insert_method_at not in s:
    raise SystemExit('style_ui method marker not found')
s=s.replace(insert_method_at,profile_methods+insert_method_at,1)

# AI 준비 UI를 2개 모델 설치 + 현재 생성 모델 선택 구조로 확장합니다.
old_ready_ui = """        self.engine_state=ttk.Label(ready,text='엔진 상태 확인 중...',style='Ready.TLabel'); self.engine_state.pack(anchor='w',pady=(6,2))
        self.model_state=ttk.Label(ready,text='모델 상태 확인 중...',style='Ready.TLabel'); self.model_state.pack(anchor='w',pady=(0,8))
        self.btn_engine=ttk.Button(ready,text='1. AI 엔진 설치 / 복구',style='Ready.TButton',command=self.install_engine); self.btn_engine.pack(fill='x',pady=4)
        self.btn_model=ttk.Button(ready,text='2. 모델 다운로드 / 확인',style='Ready.TButton',command=self.download_model); self.btn_model.pack(fill='x',pady=4)
        ttk.Label(ready,text='처음 실행 시 위 1 → 2 순서로 진행해 주세요.',style='Ready.TLabel',wraplength=340).pack(anchor='w',pady=(6,0))
"""
new_ready_ui = """        self.engine_state=ttk.Label(ready,text='엔진 상태 확인 중...',style='Ready.TLabel'); self.engine_state.pack(anchor='w',pady=(6,2))
        self.model_state=ttk.Label(ready,text='모델 상태 확인 중...',style='Ready.TLabel',wraplength=340); self.model_state.pack(anchor='w',pady=(0,8))
        self.btn_engine=ttk.Button(ready,text='1. AI 엔진 설치 / 복구',style='Ready.TButton',command=self.install_engine); self.btn_engine.pack(fill='x',pady=4)
        self.btn_fast=ttk.Button(ready,text='2. 빠른 모델 설치 / 확인',style='Ready.TButton',command=lambda:self.download_profile('빠른 모델 (SSD-1B)')); self.btn_fast.pack(fill='x',pady=4)
        self.btn_quality=ttk.Button(ready,text='3. 고품질 SDXL 설치 / 확인',style='Ready.TButton',command=lambda:self.download_profile('고품질 모델 (SDXL Base)')); self.btn_quality.pack(fill='x',pady=4)
        ttk.Label(ready,text='현재 생성 모델',style='Ready.TLabel').pack(anchor='w',pady=(8,2))
        self.model_profile_var=tk.StringVar(value=self.cfg.get('model_profile',DEFAULT_PROFILE))
        self.model_profile_combo=ttk.Combobox(ready,textvariable=self.model_profile_var,values=list(MODEL_PROFILES),state='readonly'); self.model_profile_combo.pack(fill='x')
        self.model_profile_combo.bind('<<ComboboxSelected>>',self.profile_change)
        self.model_desc=ttk.Label(ready,text='',style='Ready.TLabel',wraplength=340); self.model_desc.pack(anchor='w',pady=(4,0))
        ttk.Label(ready,text='처음에는 1 → 2 순서로 설치하면 바로 테스트할 수 있습니다.',style='Ready.TLabel',wraplength=340).pack(anchor='w',pady=(6,0))
"""
if old_ready_ui not in s:
    raise SystemExit('ready UI block not found')
s=s.replace(old_ready_ui,new_ready_ui,1)

# 상태 표시를 두 모델 각각 확인하도록 변경합니다.
old_refresh = """    def refresh_engine(self):
        if self.closing:return
        er=engine_ready(); mr=model_ready(self.cfg['model_id'],self.cfg['model_dir']) if er else False
        self.engine_state.config(text='엔진 상태: 설치됨' if er else '엔진 상태: 설치 필요')
        self.model_state.config(text='모델 상태: 준비됨' if mr else '모델 상태: 다운로드 필요')
        self.btn_model.config(state='normal' if er else 'disabled')
"""
new_refresh = """    def refresh_engine(self):
        if self.closing:return
        er=engine_ready()
        fast_name='빠른 모델 (SSD-1B)'; quality_name='고품질 모델 (SDXL Base)'
        fast_ready=model_ready(MODEL_PROFILES[fast_name]['id'],self.cfg['model_dir']) if er else False
        quality_ready=model_ready(MODEL_PROFILES[quality_name]['id'],self.cfg['model_dir']) if er else False
        current=self.cfg.get('model_profile',DEFAULT_PROFILE)
        current_ready=model_ready(MODEL_PROFILES[current]['id'],self.cfg['model_dir']) if er else False
        self.engine_state.config(text='엔진 상태: 설치됨' if er else '엔진 상태: 설치 필요')
        self.model_state.config(text=('빠른 모델: '+('준비됨' if fast_ready else '다운로드 필요')+' / 고품질: '+('준비됨' if quality_ready else '다운로드 필요')+'\n현재 선택: '+current+' - '+('사용 가능' if current_ready else '설치 필요')))
        self.model_desc.config(text=MODEL_PROFILES[current]['desc'])
        state='normal' if er else 'disabled';self.btn_fast.config(state=state);self.btn_quality.config(state=state)
"""
if old_refresh not in s:
    raise SystemExit('refresh_engine block not found')
s=s.replace(old_refresh,new_refresh,1)

# 일반 download_model은 현재 선택된 모델을 다운로드합니다.
old_download_head = "    def download_model(self):\n        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n"
new_download_head = "    def download_model(self):\n        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n        self._apply_profile_values(self.cfg.get('model_profile',DEFAULT_PROFILE),save=True)\n"
if old_download_head not in s:
    raise SystemExit('download_model header not found')
s=s.replace(old_download_head,new_download_head,1)

# 생성 전 현재 선택 모델 설치 여부를 명확히 검사합니다.
old_generate_guard = "        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n        p,n=self.prompt(); sub=self.v['sub'].get(); self.write('생성 시작: '+sub)\n"
new_generate_guard = "        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n        if not model_ready(self.cfg['model_id'],self.cfg['model_dir']):return messagebox.showwarning('안내','현재 선택한 모델을 먼저 설치해 주세요.')\n        p,n=self.prompt(); sub=self.v['sub'].get(); self.write('생성 시작: '+sub+' / '+self.cfg.get('model_profile',DEFAULT_PROFILE))\n"
if old_generate_guard not in s:
    raise SystemExit('generate guard block not found')
s=s.replace(old_generate_guard,new_generate_guard,1)

old_set_guard = "        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n        types=['한손검','투구','갑옷','반지'];name=self.setname.get().strip() or '이름 없는 세트'\n"
new_set_guard = "        if not engine_ready():return messagebox.showwarning('안내','AI 엔진 설치 / 복구를 먼저 실행해 주세요.')\n        if not model_ready(self.cfg['model_id'],self.cfg['model_dir']):return messagebox.showwarning('안내','현재 선택한 모델을 먼저 설치해 주세요.')\n        types=['한손검','투구','갑옷','반지'];name=self.setname.get().strip() or '이름 없는 세트'\n"
if old_set_guard not in s:
    raise SystemExit('set guard block not found')
s=s.replace(old_set_guard,new_set_guard,1)

s=s.replace("APP='검은 성흔 Item AI v8'","APP='검은 성흔 Item AI v9'",1)

required=[
    "APP='검은 성흔 Item AI v9'",
    "segmind/SSD-1B",
    "stabilityai/stable-diffusion-xl-base-1.0",
    "빠른 모델 설치 / 확인",
    "고품질 SDXL 설치 / 확인",
    "현재 생성 모델",
    "model_profile_combo",
    "download_profile",
    "local_dir=str(root)",
    "local_files_only=True",
    "left_scroll=ttk.Scrollbar",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s:
        raise SystemExit('v9 marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Item AI v9 patch applied')
