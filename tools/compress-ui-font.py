"""Lossless WOFF2 packaging; retain every glyph, outline and advance width.
Requires fonttools[woff] and brotli. The original TTF remains in the source bundle.
"""
from pathlib import Path
import hashlib,json,shutil
from fontTools.ttLib import TTFont
from fontTools.pens.recordingPen import RecordingPen
root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets/fonts';source=root/'third-party/fonts/ZCOOLKuaiLe-Regular.ttf'
source.parent.mkdir(parents=True,exist_ok=True)
original=assets/'ZCOOLKuaiLe-Regular.ttf'
if not source.exists():shutil.copy2(original,source)
target=assets/'ZCOOLKuaiLe-Regular.woff2';font=TTFont(source);font.flavor='woff2';font.save(target)
before=TTFont(source);after=TTFont(target)
assert before.getGlyphOrder()==after.getGlyphOrder()
assert before.getBestCmap()==after.getBestCmap() and before['hmtx'].metrics==after['hmtx'].metrics
old=before.getGlyphSet();new=after.getGlyphSet()
for name in before.getGlyphOrder():
    a=RecordingPen();b=RecordingPen();old[name].draw(a);new[name].draw(b);assert a.value==b.value,name
if original.exists():assert original.read_bytes()==source.read_bytes();original.unlink()
result={'format':'WOFF2','sourceBytes':source.stat().st_size,'bytes':target.stat().st_size,'glyphs':len(old),'allOutlinesAndMetricsUnchanged':True,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
(root/'third-party/fonts/compression.json').write_text(json.dumps(result,indent=2)+'\n','utf-8');print(json.dumps(result))
