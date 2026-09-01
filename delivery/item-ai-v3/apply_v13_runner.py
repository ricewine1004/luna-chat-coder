from pathlib import Path
import subprocess, sys

base=Path(__file__).resolve().parent
src=(base/'apply_v13_patch.py').read_text(encoding='utf-8')
old="end=s.index('\\ndef _hidden_subprocess_kwargs():',start)"
new="end=s.index('\\nLEGACY_MODELS=',start)"
if old not in src:
    raise SystemExit('v13 patch range marker not found')
patched=src.replace(old,new,1)
tmp=base/'_apply_v13_runtime_fixed.py'
tmp.write_text(patched,encoding='utf-8')
try:
    subprocess.check_call([sys.executable,str(tmp)])
finally:
    try: tmp.unlink()
    except Exception: pass
print('Corrected Item AI v13 patch applied')
