from pathlib import Path
import subprocess
import sys

p=Path(__file__).with_name('item_ai_app.py')
s=p.read_text(encoding='utf-8')

# v10까지 준비
if "APP='검은 성흔 Item AI v10'" not in s:
    subprocess.check_call([sys.executable,str(Path(__file__).with_name('apply_v10_patch.py'))])
    s=p.read_text(encoding='utf-8')

if "APP='검은 성흔 Item AI v10'" not in s:
    raise SystemExit('v10 source was not prepared')

# 검은 성흔 전용 아트 디렉션을 모든 아이템에 공통 적용합니다.
start=s.index('    def prompt(self')
end=s.index('\n    def load_pipe(self):',start)
new_prompt=r'''    def prompt(self,subtype=None):
        cat=self.v['cat'].get(); sub=subtype or self.v['sub'].get(); rar=self.v['rar'].get(); mat=self.v['mat'].get(); elm=self.v['elm'].get(); mood=self.v['mood'].get(); extra=self.desc.get('1.0','end').strip()

        # 검은 성흔 공통 디자인 언어: 서로 다른 아이템을 생성해도 한 게임의 자산처럼 보이도록 고정합니다.
        black_stigma_core=[
            'official Black Stigma dark fantasy ARPG item art direction',
            'grim Korean gothic dark fantasy aesthetic',
            'blackened iron, aged silver and weathered dark leather as the dominant craftsmanship language where physically appropriate',
            'subtle engraved stigma runes, restrained thorn-like filigree and ancient scar motifs',
            'hand-forged relic craftsmanship, believable materials, slightly worn edges, premium game asset finish',
            'serious mature dark fantasy, ominous but elegant, never cute or playful',
            'one strong readable silhouette with disciplined ornament placement',
            'front three-quarter inventory presentation, centered object occupying most of the square frame',
            'consistent cool-neutral studio lighting with restrained warm or magical focal accents',
            'soft neutral charcoal-to-gray background, subtle contact shadow only, no environment scene',
            'high contrast around the outer silhouette so the item remains readable at small inventory icon size',
            'controlled color palette; reserve saturated color for gameplay meaning, magic, gems, liquid or rarity accents',
        ]

        category_rules={
            '무기': 'combat-ready functional weapon, dark forged metal structure, believable edge and grip construction, rune channels and restrained gothic detailing, dangerous but not oversized or cartoonish',
            '방어구': 'layered protective construction, blackened plates or dark leather with aged silver fittings, practical joints and believable protection, restrained heraldic stigma motifs',
            '악세사리': 'compact ancient wearable relic, precise metalwork, carved stigma sigil, gemstone or magical focal point kept visually readable, elegant rather than gaudy',
            '물약': 'distinctive alchemical vessel with dark gothic frame, aged silver or blackened metal fittings, clear glass area large enough to read the liquid color, functional fantasy medicine rather than poison unless explicitly requested',
            '커런시': 'valuable ritual currency or relic token, strong emblematic silhouette, tactile ancient material, unmistakable collectible game currency presentation',
            '재료': 'tactile crafting resource with believable raw material texture, ritual or monster-origin clues, readable single-object presentation, useful rather than decorative clutter',
            '잡템': 'worn story-rich loot object from the Black Stigma world, aged and damaged but visually readable, grounded medieval dark fantasy construction',
        }

        rarity_rules={
            '일반': 'common rarity visual language: practical construction, minimal ornament, almost no magical glow, muted accents',
            '마법': 'magic rarity visual language: one restrained magical accent, subtle rune glow, slightly refined craftsmanship',
            '희귀': 'rare rarity visual language: richer material contrast, controlled ornate engraving, two or three premium focal details, stronger but disciplined magical accent',
            '고유': 'unique rarity visual language: memorable signature silhouette, one distinctive asymmetric or symbolic motif, handcrafted relic identity without visual clutter',
            '전설': 'legendary rarity visual language: iconic silhouette, masterwork material treatment, prestigious stigma motif, powerful focal glow and exceptional detail while remaining readable as an inventory icon',
        }

        parts=[
            'dark fantasy action RPG inventory item icon',
            KO.get(sub,sub), KO.get(rar,rar),
            *black_stigma_core,
            category_rules.get(cat,''), rarity_rules.get(rar,RS.get(rar,'')),
            'single isolated item', 'centered composition', 'clean readable silhouette',
            'highly detailed game asset', 'no character', 'no hands', 'no text', 'no watermark'
        ]

        negative=(
            'person, human, character, hand, holding item, multiple items, scenery, landscape, text, letters, logo, watermark, frame, UI, '
            'low quality, blurry, cropped, chibi, cute, kawaii, childish, preschool drawing, toy, plastic toy, cartoon prop, goofy proportions, '
            'bright rainbow palette, excessive neon colors, modern product design, sci-fi technology, cyberpunk, firearm, futuristic machinery, '
            'overdecorated unreadable silhouette, excessive particle effects, huge background effects, busy environment, floating presentation pedestal'
        )

        # 물약은 세계관 외형을 유지하되 기능 색상은 절대 어둡게 묻지 않도록 v10 규칙을 유지/강화합니다.
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
                'the vessel exterior follows Black Stigma blackened metal and aged silver gothic craftsmanship, but the glass window and liquid remain visually dominant',
                'do not darken or obscure the liquid color',
                'the potion purpose must be understandable from the liquid color at a glance'
            ])
            negative += ', '+extra_negative

        if mat!='자동': parts.append('primary material emphasis: '+KO.get(mat,mat)+', integrated into the Black Stigma material language')
        if elm!='없음': parts.append('imbued with '+KO.get(elm,elm)+' energy as a restrained focal effect, not a full-frame color wash')
        if mood!='검은 성흔 기본': parts.append(KO.get(mood,mood)+' atmosphere while preserving the core Black Stigma art direction and item readability')
        if self.setname.get().strip():
            parts.append('belongs to the '+self.setname.get().strip()+' item set, repeat the same stigma emblem family, metal treatment, trim geometry and accent logic across every piece of this set')
        if extra: parts.append(extra)
        return ', '.join(x for x in parts if x), negative
'''
s=s[:start]+new_prompt+s[end:]

s=s.replace("APP='검은 성흔 Item AI v10'","APP='검은 성흔 Item AI v11'",1)

required=[
    "APP='검은 성흔 Item AI v11'",
    'official Black Stigma dark fantasy ARPG item art direction',
    'blackened iron, aged silver and weathered dark leather',
    'subtle engraved stigma runes',
    'serious mature dark fantasy, ominous but elegant',
    'controlled color palette; reserve saturated color for gameplay meaning',
    "category_rules={",
    "rarity_rules={",
    'common rarity visual language',
    'legendary rarity visual language',
    'preserving the core Black Stigma art direction and item readability',
    'repeat the same stigma emblem family',
    'chibi, cute, kawaii, childish, preschool drawing',
    'vivid ruby red to crimson healing liquid',
    'luminous sapphire blue to cyan magical liquid',
    '✅ 이미지 4장 생성 완료',
    'segmind/SSD-1B',
    'local_dir=str(root)',
    'left_scroll=ttk.Scrollbar',
    'WM_DELETE_WINDOW',
]
for marker in required:
    if marker not in s:
        raise SystemExit('v11 marker missing: '+marker)

p.write_text(s,encoding='utf-8')
print('Item AI v11 Black Stigma style-lock patch applied')
