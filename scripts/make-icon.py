from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import sys
root=Path(__file__).resolve().parents[1]
font=Path(sys.argv[1]) if len(sys.argv)>1 else Path.home()/'astrolab-type/releases/ASTROLAB-Sans-Fresh-0.5/ASTROLABSans-Regular.ttf'
n=1024
im=Image.new('RGBA',(n,n))
d=ImageDraw.Draw(im)
mask=Image.new('L',(n,n)); md=ImageDraw.Draw(mask)
md.rounded_rectangle((88,88,935,935),208,fill=255)
md.rectangle((728,728,935,935),fill=255)
metal=Image.new('RGBA',(n,n))
m=ImageDraw.Draw(metal)
for y in range(n):
    t=max(0,min(1,(y-88)/848))
    v=round(252-28*t+5*__import__('math').sin(t*__import__('math').pi*2))
    m.line((0,y,n,y),fill=(v,v,min(255,v+3),255))
im.paste(metal,(0,0),mask)
d=ImageDraw.Draw(im)
f=ImageFont.truetype(str(font),590)
b=d.textbbox((0,0),'In',font=f,stroke_width=4)
d.text((512-(b[0]+b[2])/2,512-(b[1]+b[3])/2),'In',font=f,fill='#252A32',stroke_width=4,stroke_fill='#252A32')
d.polygon([(776,935),(935,935),(935,776)],fill='#2859CC')
im.save(root/'design/AppIcon.png')
# Legacy launcher artwork; fixed silhouette preserves the signature.
for density,size in [('mdpi',48),('hdpi',72),('xhdpi',96),('xxhdpi',144),('xxxhdpi',192)]:
    p=root/'app/src/main/res'/('mipmap-'+density);p.mkdir(parents=True,exist_ok=True)
    im.resize((size,size),Image.Resampling.LANCZOS).save(p/'ic_launcher.png')
