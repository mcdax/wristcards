"""Draws the Wristcards launcher icon (blue background, large watch face, two fanned cards)."""
import sys
from PIL import Image, ImageDraw
S=2048; c=S/2
BLUE=(59,125,216,255); WHITE=(255,255,255,255); ORANGE=(242,166,90,255); CARD=(74,144,226,255)
im=Image.new("RGBA",(S,S),(0,0,0,0)); d=ImageDraw.Draw(im)
d.ellipse((0,0,S-1,S-1),fill=BLUE)
def circ(r,fill): d.ellipse((c-r,c-r,c+r,c+r),fill=fill)
import math
# Krone rechts und Lünette mit Minutenstrichen, damit der Ring als Uhr erkennbar ist.
d.rounded_rectangle((c+S*0.40,c-S*0.06,c+S*0.475,c+S*0.06),radius=S*0.02,fill=WHITE)
circ(S*0.43,WHITE); circ(S*0.385,BLUE); circ(S*0.37,WHITE)
for i in range(60):
    a=math.radians(i*6); big=i%15==0; mid=i%5==0
    if not mid: continue
    r1=S*0.40; r2=S*(0.425 if big else 0.418)
    wdt=int(S*(0.018 if big else 0.011))
    d.line((c+r1*math.sin(a),c-r1*math.cos(a),c+r2*math.sin(a),c-r2*math.cos(a)),fill=BLUE,width=wdt)
def card(cx,cy,w,h,ang,fill,r):
    layer=Image.new("RGBA",(S,S),(0,0,0,0)); ImageDraw.Draw(layer).rounded_rectangle((cx-w/2,cy-h/2,cx+w/2,cy+h/2),radius=r,fill=fill)
    return layer.rotate(ang,center=(cx,cy),resample=Image.BICUBIC)
w,h=S*0.34,S*0.45; r=S*0.04
fx,fy=c+S*0.065,c+S*0.01
for L in (card(c-S*0.075,c+S*0.005,w,h,14,ORANGE,r), card(fx,fy,w+S*0.03,h+S*0.03,-10,WHITE,r*1.3), card(fx,fy,w,h,-10,CARD,r)):
    im.alpha_composite(L)
chk=Image.new("RGBA",(S,S),(0,0,0,0)); cd=ImageDraw.Draw(chk)
pts=[(fx-S*0.08,fy),(fx-S*0.022,fy+S*0.058),(fx+S*0.085,fy-S*0.062)]
cd.line(pts,fill=ORANGE,width=int(S*0.036),joint="curve")
for p in (pts[0],pts[2]): cd.ellipse((p[0]-S*0.018,p[1]-S*0.018,p[0]+S*0.018,p[1]+S*0.018),fill=ORANGE)
im.alpha_composite(chk.rotate(-10,center=(fx,fy),resample=Image.BICUBIC))
im.save("art/icon-2048.png"); im.resize((512,512),Image.LANCZOS).save("art/icon-512.png")
res="app/app/src/main/res"
for dn,s in {"mdpi":48,"hdpi":72,"xhdpi":96,"xxhdpi":144,"xxxhdpi":192}.items():
    x=im.resize((s,s),Image.LANCZOS); x.save(f"{res}/mipmap-{dn}/ic_launcher.png"); x.save(f"{res}/mipmap-{dn}/ic_launcher_round.png")
print("ok")
