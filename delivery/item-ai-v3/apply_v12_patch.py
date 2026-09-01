from pathlib import Path
import subprocess
import sys

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')

# v11까지 준비
if "APP='검은 성흔 Item AI v11'" not in s:
    subprocess.check_call([sys.executable,str(Path(__file__).with_name('apply_v11_patch.py'))])
    s=p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v11'" not in s:
    raise SystemExit('v11 source was not prepared')

# 모델도 엔진과 동일하게 실행 파일 버전 폴더 밖의 공용 경로에 영구 저장합니다.
old_paths="""MODELS=DATA/'models'; MODELS.mkdir(exist_ok=True)
OUTPUTS=DATA/'outputs'; OUTPUTS.mkdir(exist_ok=True)
"""
new_paths="""LEGACY_MODELS=DATA/'models'; LEGACY_MODELS.mkdir(exist_ok=True)
SHARED_MODEL_ROOT=(Path(BASE.anchor)/'BlackStigmaItemAI_Models') if BASE.anchor else (DATA/'shared_models')
SHARED_MODEL_ROOT.mkdir(parents=True,exist_ok=True)
MODELS=SHARED_MODEL_ROOT
OUTPUTS=DATA/'outputs'; OUTPUTS.mkdir(exist_ok=True)
"""
if old_paths not in s:
    raise SystemExit('model path block not found')
s=s.replace(old_paths,new_paths,1)

# 기존 버전에서 이미 다운로드한 local_dir 모델을 찾아 공용 모델 폴더로 이관합니다.
marker="def local_model_path(model_id, model_dir):\n"
if marker not in s:
    raise SystemExit('local_model_path marker not found')
helpers=r'''def _looks_like_model_dir(path):
    try:
        path=Path(path)
        return (path/'model_index.json').is_file() and (path/'unet').exists() and (path/'vae').exists()
    except Exception:
        return False


def _legacy_model_roots():
    candidates=[]
    # 현재 버전 폴더의 과거 저장 위치
    candidates.append(LEGACY_MODELS)
    # v9/v10/v11처럼 같은 상위 폴더에 압축 해제한 이전 버전 폴더를 탐색
    try:
        parent=BASE.parent
        for child in parent.iterdir():
            if not child.is_dir() or child.resolve()==BASE.resolve():
                continue
            old=child/'BlackStigmaItemAI_Data'/'models'
            if old.is_dir(): candidates.append(old)
    except Exception:
        pass
    # 중복 제거
    out=[]; seen=set()
    for x in candidates:
        try:key=str(Path(x).resolve()).lower()
        except Exception:key=str(x).lower()
        if key not in seen:
            seen.add(key);out.append(Path(x))
    return out


def migrate_legacy_models():
    SHARED_MODEL_ROOT.mkdir(parents=True,exist_ok=True)
    moved=[]
    for legacy_root in _legacy_model_roots():
        if not legacy_root.is_dir():
            continue
        try:
            children=list(legacy_root.iterdir())
        except Exception:
            continue
        for src in children:
            if not src.is_dir() or not _looks_like_model_dir(src):
                continue
            dst=SHARED_MODEL_ROOT/src.name
            if _looks_like_model_dir(dst):
                continue
            try:
                # 동일 드라이브에서는 거의 즉시 rename되므로 수 GB 모델을 다시 복사/다운로드하지 않습니다.
                if src.drive.lower()==dst.drive.lower():
                    src.rename(dst)
                else:
                    import shutil
                    shutil.copytree(src,dst,dirs_exist_ok=True)
                moved.append((str(src),str(dst)))
            except Exception as e:
                log('기존 모델 자동 이관 경고: '+str(e))
    for old,new in moved:
        log('기존 모델 공용 폴더 이관 완료: '+old+' -> '+new)
    return moved


'''
s=s.replace(marker,helpers+marker,1)

# config.json에 이전 버전의 개별 모델 경로가 저장되어 있어도 항상 공용 모델 경로를 사용합니다.
old_post_cfg="""        if self.cfg.get('model_profile') not in MODEL_PROFILES:
            found=next((name for name,info in MODEL_PROFILES.items() if info['id']==self.cfg.get('model_id')),DEFAULT_PROFILE)
            self.cfg['model_profile']=found
        self._apply_profile_values(self.cfg['model_profile'], save=False)
        self.style_ui(); self.ui(); self.refresh_engine(); self.write('프로그램 시작')
"""
new_post_cfg="""        if self.cfg.get('model_profile') not in MODEL_PROFILES:
            found=next((name for name,info in MODEL_PROFILES.items() if info['id']==self.cfg.get('model_id')),DEFAULT_PROFILE)
            self.cfg['model_profile']=found
        moved=migrate_legacy_models()
        self.cfg['model_dir']=str(SHARED_MODEL_ROOT)
        self._apply_profile_values(self.cfg['model_profile'], save=False)
        self._save_cfg()
        self.style_ui(); self.ui(); self.refresh_engine(); self.write('프로그램 시작')
        self.write('공용 모델 폴더: '+str(SHARED_MODEL_ROOT))
        if moved:self.write('✅ 기존 모델 공용 폴더 자동 이관 완료 ('+str(len(moved))+'개)')
"""
if old_post_cfg not in s:
    raise SystemExit('post config v9 block not found')
s=s.replace(old_post_cfg,new_post_cfg,1)

# 설정 저장 시에도 모델 경로는 공용 경로로 강제해서 버전 폴더별 경로로 되돌아가지 않게 합니다.
old_save="""    def _save_cfg(self):
        try:
            Path(self.cfg['model_dir']).mkdir(parents=True,exist_ok=True)
            Path(self.cfg['output_dir']).mkdir(parents=True,exist_ok=True)
            CFG.write_text(json.dumps(self.cfg,ensure_ascii=False,indent=2),encoding='utf-8')
"""
new_save="""    def _save_cfg(self):
        try:
            self.cfg['model_dir']=str(SHARED_MODEL_ROOT)
            SHARED_MODEL_ROOT.mkdir(parents=True,exist_ok=True)
            Path(self.cfg['output_dir']).mkdir(parents=True,exist_ok=True)
            CFG.write_text(json.dumps(self.cfg,ensure_ascii=False,indent=2),encoding='utf-8')
"""
if old_save not in s:
    raise SystemExit('_save_cfg block not found')
s=s.replace(old_save,new_save,1)

# 설정 창에 예전 모델 경로 입력 UI가 남아 있다면 저장 시 공용 경로로 강제합니다.
s=s.replace("            self.cfg['model_dir']=md.get().strip();self.cfg['output_dir']=od.get().strip();self.cfg['model_id']=mi.get().strip();self.cfg['width']=int(w.get());self.cfg['height']=int(h.get());self.cfg['steps']=int(st.get());self.cfg['guidance']=float(g.get())",
            "            self.cfg['model_dir']=str(SHARED_MODEL_ROOT);self.cfg['output_dir']=od.get().strip();self.cfg['model_id']=mi.get().strip();self.cfg['width']=int(w.get());self.cfg['height']=int(h.get());self.cfg['steps']=int(st.get());self.cfg['guidance']=float(g.get())")

# AI 준비 상태에 공용 모델 폴더도 표시합니다.
old_desc="""        self.model_desc.config(text=MODEL_PROFILES[current]['desc'])
"""
new_desc="""        self.model_desc.config(text=MODEL_PROFILES[current]['desc']+'\\n공용 모델 폴더: '+str(SHARED_MODEL_ROOT))
"""
if old_desc not in s:
    raise SystemExit('model description block not found')
s=s.replace(old_desc,new_desc,1)

s=s.replace("APP='검은 성흔 Item AI v11'","APP='검은 성흔 Item AI v12'",1)

required=[
    "APP='검은 성흔 Item AI v12'",
    "BlackStigmaItemAI_Models",
    "SHARED_MODEL_ROOT",
    "migrate_legacy_models()",
    "기존 모델 공용 폴더 자동 이관 완료",
    "self.cfg['model_dir']=str(SHARED_MODEL_ROOT)",
    "공용 모델 폴더: ",
    "BlackStigmaItemAI_Engine",
    "segmind/SSD-1B",
    "stabilityai/stable-diffusion-xl-base-1.0",
    "official Black Stigma dark fantasy ARPG item art direction",
    "vivid ruby red to crimson healing liquid",
    "✅ 이미지 4장 생성 완료",
    "local_dir=str(root)",
    "local_files_only=True",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s:
        raise SystemExit('v12 marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Item AI v12 shared persistent model storage patch applied')
