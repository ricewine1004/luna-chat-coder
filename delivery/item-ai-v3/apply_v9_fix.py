from pathlib import Path

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')
old="')+'\n현재 선택: '+current"
new="')+'\\n현재 선택: '+current"
if old not in s:
    raise SystemExit('v9 newline fix target not found')
s=s.replace(old,new,1)
p.write_text(s,encoding='utf-8')
print('Item AI v9 newline fix applied')
