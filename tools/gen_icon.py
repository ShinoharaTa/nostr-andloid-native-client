import math, sys
from PIL import Image, ImageDraw

BG=(12,12,16); WAVE=(138,138,147); NODE=(167,168,179); WHITE=(236,237,241)
BGx="#0C0C10"; WAVEx="#8A8A93"; NODEx="#A7A8B3"; WHITEx="#ECEDF1"

# --- geometry (108 viewport) ---
def wpts(y0,amp,wl,phase,x0=8,x1=100,n=120):
    return [(x0+(x1-x0)*i/n, y0+amp*math.sin(2*math.pi*((x0+(x1-x0)*i/n)-x0)/wl+phase)) for i in range(n+1)]
def diag(p0,p1,amp,humps,n=90):
    (x0,y0),(x1,y1)=p0,p1;dx,dy=x1-x0,y1-y0;L=math.hypot(dx,dy);px,py=-dy/L,dx/L
    o=[]
    for i in range(n+1):
        t=i/n;bx,by=x0+dx*t,y0+dy*t;off=amp*math.sin(math.pi*humps*t)
        o.append((bx+px*off,by+py*off))
    return o
def yat(y0,amp,wl,phase,x,x0=8): return y0+amp*math.sin(2*math.pi*(x-x0)/wl+phase)

# 波は元イメージ寄りに見えるよう振幅/本数を確保（3波長）。N は約65%に縮小＆細く。
WA=wpts(46,13,52,0.0); WB=wpts(62,10,66,math.pi*0.55); WC=wpts(54,8,34,math.pi*1.1)
NL=[(42,40),(42,69)]; NR=[(66,40),(66,69)]
ND=diag((42,40),(66,69),-4.0,1)
NODES=[(8,yat(46,13,52,0,8)),(100,yat(46,13,52,0,100)),
       (8,yat(62,10,66,math.pi*0.55,8)),(100,yat(62,10,66,math.pi*0.55,100)),
       (8,yat(54,8,34,math.pi*1.1,8)),(100,yat(54,8,34,math.pi*1.1,100))]
NW=5.5  # N stroke width（細め）

def polystr(pts):
    return "M%.2f,%.2f "%pts[0]+" ".join("L%.2f,%.2f"%p for p in pts[1:])
def circ(cx,cy,r): return f"M{cx-r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,0 {2*r:.2f},0 a{r:.2f},{r:.2f} 0 1,0 {-2*r:.2f},0 Z"

def path(pathdata,stroke=None,sw=None,fill=None):
    a=f'    <path android:pathData="{pathdata}"'
    if stroke: a+=f' android:strokeColor="{stroke}" android:strokeWidth="{sw}" android:strokeLineCap="round" android:strokeLineJoin="round"'
    if fill: a+=f' android:fillColor="{fill}"'
    return a+"/>"

def vector(body):
    return ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'+body+'\n</vector>\n')

def fg(colorW,colorWave,colorNode):
    b=[]
    b.append(path(polystr(WA),colorWave,"2.2")); b.append(path(polystr(WB),colorWave,"2.2")); b.append(path(polystr(WC),colorWave,"2.2"))
    for cx,cy in NODES: b.append(path(circ(cx,cy,2.4),fill=colorNode))
    b.append(path(polystr(NL),colorW,NW)); b.append(path(polystr(NR),colorW,NW)); b.append(path(polystr(ND),colorW,NW))
    return "\n".join(b)

root="composeApp/src/androidMain/res"
open(f"{root}/drawable/ic_launcher_foreground.xml","w").write(vector(fg(WHITEx,WAVEx,NODEx)))
open(f"{root}/drawable/ic_launcher_monochrome.xml","w").write(vector(fg("#000000","#000000","#000000")))
open(f"{root}/drawable/ic_launcher_background.xml","w").write(vector(path("M0,0h108v108h-108z",fill=BGx)))

# --- PIL preview (mirror) ---
S=432; sc=S/108
def X(v): return v*sc
img=Image.new("RGB",(S,S),BG); d=ImageDraw.Draw(img)
for pts in (WA,WB,WC): d.line([(X(x),X(y)) for x,y in pts],fill=WAVE,width=int(2.2*sc),joint="curve")
for cx,cy in NODES:
    r=2.4*sc; d.ellipse([X(cx)-r,X(cy)-r,X(cx)+r,X(cy)+r],fill=NODE)
w=int(NW*sc)
for seg in (NL,NR,ND):
    d.line([(X(x),X(y)) for x,y in seg],fill=WHITE,width=w,joint="curve")
    for pt in (seg[0],seg[-1]):
        r=w/2; d.ellipse([X(pt[0])-r,X(pt[1])-r,X(pt[0])+r,X(pt[1])+r],fill=WHITE)
# rounded-square mask preview
out="/private/tmp/claude-501/-Users-shino3-Project-nostr-deck-client/13f83b62-6237-412f-972c-ebe4f5311cd8/scratchpad"
img.save(f"{out}/icon_preview.png")
img.resize((96,96),Image.LANCZOS).save(f"{out}/icon_small.png")
print("written")
