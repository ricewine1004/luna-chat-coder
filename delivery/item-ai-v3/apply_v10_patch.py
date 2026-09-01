from pathlib import Path
import subprocess
import sys

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')

# v9까지 준비
if "APP='검은 성흔 Item AI v9'" not in s:
    subprocess.check_call([sys.executable,str(Path(__file__).with_name('apply_v9_patch.py'))])
    fix=Path(__file__).with_name('apply_v9_fix.py')
    if fix.exists(): subprocess.check_call([sys.executable,str(fix)])
    s=p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v9'" not in s:
    raise SystemExit('v9 source was not prepared')

# 물약 종류별 색상/기능 분위기를 명확히 고정합니다.
start=s.index('    def prompt(self')
end=s.index('\n    def load_pipe(self):',start)
new_prompt=r'''    def prompt(self,subtype=None):
        cat=self.v['cat'].get(); sub=subtype or self.v['sub'].get(); rar=self.v['rar'].get(); mat=self.v['mat'].get(); elm=self.v['elm'].get(); mood=self.v['mood'].get(); extra=self.desc.get('1.0','end').strip()
        parts=[
            'dark fantasy action RPG inventory item icon',
            KO.get(sub,sub), KO.get(rar,rar), RS.get(rar,''),
            'single isolated item', 'centered composition', 'clean readable silhouette',
            'highly detailed game asset', 'studio lighting', 'plain neutral background',
            'grim gothic fantasy craftsmanship', 'no character', 'no hands', 'no text', 'no watermark'
        ]
        negative='person, human, character, hand, holding item, multiple items, scenery, landscape, text, letters, logo, watermark, frame, UI, low quality, blurry, cropped'

        # 병과 장식은 어두운 세계관을 유지하되, 액체는 기능을 직관적으로 읽을 수 있게 강제합니다.
        potion_rules={
            '생명력 물약':(
                'a clearly visible vivid ruby red to crimson healing liquid filling most of the bottle, warm life-restoring inner glow, healthy restorative alchemy, appetizing and safe magical medicine, strong saturated red liquid that remains bright even inside a dark gothic bottle, the liquid must unmistakably read as a health potion',
                'black liquid, purple liquid, gray liquid, colorless liquid, empty bottle, poisonous appearance, dead liquid, muddy liquid'
            ),
            '마나 물약':(
                'a clearly visible luminous sapphire blue to cyan magical liquid filling most of the bottle, bright arcane inner glow, clean mana energy, saturated blue liquid that remains easy to read inside a dark gothic bottle, unmistakably a mana potion',
                'black liquid, red liquid, brown liquid, gray liquid, empty bottle, muddy liquid, poisonous appearance'
            ),
            '해독 물약':(
                'a clearly visible vivid emerald green herbal antidote liquid, fresh cleansing glow, purified medicinal alchemy, saturated green contents, safe detoxifying potion, readable green liquid inside a dark fantasy bottle',
                'black liquid, muddy brown liquid, rotten appearance, empty bottle, toxic sludge, purple-black poison'
            ),
            '강화 물약':(
                'a clearly visible radiant amber orange and golden enhancement elixir, energetic golden sparks inside the liquid, empowering warm glow, premium strengthening potion, bright readable amber-gold contents',
                'black liquid, dull gray liquid, empty bottle, lifeless liquid, murky sludge'
            ),
            '저항 물약':(
                'a clearly visible luminous violet liquid with silver-blue protective highlights, shimmering barrier-like energy, defensive resistance elixir, bright readable magical contents that suggest protection and resilience',
                'black liquid, muddy liquid, empty bottle, lifeless liquid, toxic sludge'
            ),
        }
        if cat=='물약' and sub in potion_rules:
            positive,extra_negative=potion_rules[sub]
            parts.extend([
                positive,
                'the bottle exterior may be dark gothic metal or glass, but do not darken or obscure the liquid color',
                'the potion purpose must be understandable from the liquid color at a glance'
            ])
            negative += ', '+extra_negative

        if mat!='자동': parts.append('made with '+KO.get(mat,mat))
        if elm!='없음': parts.append('imbued with '+KO.get(elm,elm)+' energy')
        if mood!='검은 성흔 기본': parts.append(KO.get(mood,mood))
        if self.setname.get().strip(): parts.append('belongs to the '+self.setname.get().strip()+' item set, shared matching visual motif')
        if extra: parts.append(extra)
        return ', '.join(x for x in parts if x), negative
'''
s=s[:start]+new_prompt+s[end:]

# 성공 시 파일 로그뿐 아니라 화면 로그창에도 명확한 완료 표시를 남깁니다.
repls={
    "            log('AI 엔진 설치 및 import 검증 완료')":"            self.after(0,lambda:self.write('✅ AI 엔진 설치 / 복구 완료'))",
    "            log('모델 준비 완료: '+result)":"            log('모델 준비 완료: '+result)\n            self.after(0,lambda:self.write('✅ 모델 다운로드 / 확인 완료'))",
    "                self.images=arr;self.meta=records;self.after(0,self.refresh_all);log('4장 생성 완료')":"                self.images=arr;self.meta=records;self.after(0,self.refresh_all);self.after(0,lambda:self.write('✅ 이미지 4장 생성 완료'))",
    "                self.images=arr;self.meta=records;self.after(0,self.refresh_all);log('장비 세트 생성 완료')":"                self.images=arr;self.meta=records;self.after(0,self.refresh_all);self.after(0,lambda:self.write('✅ 장비 세트 4종 생성 완료'))",
    "            if not self.closing:self.after(0,lambda:self.redraw(self.sel));log('배경 제거 완료: '+str(fn))":"            if not self.closing:self.after(0,lambda:self.redraw(self.sel));self.after(0,lambda:self.write('✅ 선택 이미지 배경 제거 완료'))"
}
for old,new in repls.items():
    if old not in s:
        print('warning: completion marker source not found:',old)
    else:
        s=s.replace(old,new,1)

# 일반 완료 상태도 작업 성공 후 잠깐 명확하게 보이도록 함.
s=s.replace("APP='검은 성흔 Item AI v9'","APP='검은 성흔 Item AI v10'",1)

required=[
    "APP='검은 성흔 Item AI v10'",
    "vivid ruby red to crimson healing liquid",
    "luminous sapphire blue to cyan magical liquid",
    "vivid emerald green herbal antidote liquid",
    "radiant amber orange and golden enhancement elixir",
    "luminous violet liquid with silver-blue protective highlights",
    "do not darken or obscure the liquid color",
    "✅ 이미지 4장 생성 완료",
    "✅ 모델 다운로드 / 확인 완료",
    "left_scroll=ttk.Scrollbar",
    "segmind/SSD-1B",
    "local_dir=str(root)",
    "WM_DELETE_WINDOW",
]
for marker in required:
    if marker not in s: raise SystemExit('v10 marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Item AI v10 patch applied')
