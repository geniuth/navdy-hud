# HudView.java 의 새 렌더링을 그대로 옮긴 미리보기. 실기기 캡처가 아니라 시뮬레이션이다.
from PIL import Image, ImageDraw, ImageFont
import math, os

S = 2                      # 2배로 그린 뒤 축소해 안티에일리어싱을 흉내낸다
W, H = 640, 480
SAFE = 1/6
safeTop, safeBottom = H*SAFE, H*(1-SAFE)
horizon = safeTop + (safeBottom-safeTop)*0.30
focal, camH, MIN_X, MAX_X = W*0.625, 1.65, 2.0, 50.0

ROAD=(255,255,255); ROAD_Y=(255,220,80); SYSTEM=(80,255,120)
CAUTION=(255,200,40); DANGER=(255,60,60); BLACK=(0,0,0)

SZ_SPEED, SZ_UNIT, SZ_LABEL, SZ_SIGN, SZ_ALERT = 58, 20, 20, 30, 38
CONF_MIN = 0.25
PATH_W_M, PATH_MAX_X = 0.7, 35.0
EGO_W, EGO_L, EGO_GAP, EGO_PAD = 62.0, 74.0, 12.0, 30.0

B = "/usr/share/fonts/opentype/noto/NotoSansCJK-Bold.ttc"
R = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
f_speed = ImageFont.truetype(B, int(SZ_SPEED*S))
f_unit  = ImageFont.truetype(R, int(SZ_UNIT*S))
f_label = ImageFont.truetype(R, int(SZ_LABEL*S))
f_sign  = ImageFont.truetype(B, int(SZ_SIGN*S))
f_alert = ImageFont.truetype(B, int(SZ_ALERT*S))

def sx(x, y): return W*0.5 + focal*y/max(MIN_X, x)
def sy(x):    return horizon + focal*camH/max(MIN_X, x)
def sc(x, m): return focal*m/max(MIN_X, x)
def p(v):     return v*S

def ego_cy(): return safeBottom - EGO_PAD - EGO_L*0.5
def ego_top(): return ego_cy() - EGO_L*0.5 - EGO_GAP

def text(d, s, x, y, font, col):        # y 는 안드로이드처럼 베이스라인
    a = font.getbbox("Hg")[3] - font.getbbox("Hg")[1]
    d.text((p(x), p(y)-a*0.80), s, font=font, fill=col)

def tw(s, font): return (font.getbbox(s)[2]-font.getbbox(s)[0])/S

def dash(d, pts, col, w, pattern=(20,16)):
    on, rem, drawing = pattern[0], pattern[0], True
    for (x0,y0),(x1,y1) in zip(pts, pts[1:]):
        seg = math.hypot(x1-x0, y1-y0); t = 0.0
        while t < seg:
            step = min(rem, seg-t)
            if drawing:
                a=(x0+(x1-x0)*t/seg, y0+(y1-y0)*t/seg)
                b=(x0+(x1-x0)*(t+step)/seg, y0+(y1-y0)*(t+step)/seg)
                d.line([p(a[0]),p(a[1]),p(b[0]),p(b[1])], fill=col, width=int(w*S))
            t += step; rem -= step
            if rem <= 0.001:
                drawing = not drawing; rem = pattern[0] if drawing else pattern[1]

def poly(xs, ys):                       # 월드 → 화면, MIN_X~MAX_X 만
    out=[]
    for x,y in zip(xs,ys):
        if x < MIN_X or x > MAX_X: continue
        out.append((sx(x,y), sy(x)))
    return out

XS=[3,4.7,6.8,9.2,12,15.2,18.8,22.7,27,31.7,36.8,50]

def line(off, curve=0.0):
    return [off + curve*x*x*0.002 for x in XS]

def quad(p0,p1,p2,n=8):
    return [((1-t)**2*p0[0]+2*(1-t)*t*p1[0]+t*t*p2[0],
             (1-t)**2*p0[1]+2*(1-t)*t*p1[1]+t*t*p2[1]) for t in [i/n for i in range(n+1)]]

def ego_path():
    cx, cy = W*0.5, ego_cy(); hw, hl = EGO_W*0.5, EGO_L*0.5
    pts=[(cx-hw*0.80, cy+hl), (cx+hw*0.80, cy+hl)]
    pts += quad((cx+hw*0.80,cy+hl),(cx+hw,cy+hl*0.72),(cx+hw,cy+hl*0.30))
    pts += [(cx+hw, cy-hl*0.28)]
    pts += quad((cx+hw,cy-hl*0.28),(cx+hw*0.92,cy-hl*0.74),(cx+hw*0.52,cy-hl))
    pts += [(cx-hw*0.52, cy-hl)]
    pts += quad((cx-hw*0.52,cy-hl),(cx-hw*0.92,cy-hl*0.74),(cx-hw,cy-hl*0.28))
    pts += [(cx-hw, cy+hl*0.30)]
    pts += quad((cx-hw,cy+hl*0.30),(cx-hw,cy+hl*0.72),(cx-hw*0.80,cy+hl))
    return [(p(a),p(b)) for a,b in pts]

def render(pkt, out):
    img = Image.new("RGB", (W*S, H*S), BLACK)
    d = ImageDraw.Draw(img, "RGBA")
    stale = pkt.get("stale", False)

    if not stale:
        # 차선
        typeL, typeR = pkt.get("laneL",-1), pkt.get("laneR",-1)
        offs=[-5.4,-1.8,1.8,5.4]; confs=[0.58,0.97,0.97,0.49]
        for i,(off,c) in enumerate(zip(offs,confs)):
            if c < CONF_MIN: continue
            inner = i in (1,2)
            typ = (typeL if i==1 else typeR) if inner else -1
            side_left = i <= 1
            bsd = pkt.get("leftBsd" if side_left else "rightBsd", False)
            if bsd:
                col = DANGER if pkt.get("leftBlinker" if side_left else "rightBlinker", False) else CAUTION
            elif 20 <= typ < 30: col = ROAD_Y
            else: col = ROAD
            w = 5 if inner else 3
            pts = poly(XS, line(off, pkt.get("curve",0.0)))
            if typ >= 0 and typ % 10 == 0: dash(d, pts, col, w)
            else: d.line([p(v) for xy in pts for v in xy], fill=col, width=int(w*S), joint="curve")

        # 경로 띠 (자차 아이콘 위에서 시작)
        et = ego_top(); us=[]; vs=[]; hws=[]
        for x in XS:
            if x > PATH_MAX_X: continue
            v = sy(x)
            if v > et: continue
            us.append(sx(x, pkt.get("curve",0.0)*x*x*0.002)); vs.append(v); hws.append(sc(x,PATH_W_M)*0.5)
        if len(us) >= 2:
            band=[(u-h,v) for u,v,h in zip(us,vs,hws)] + [(u+h,v) for u,v,h in zip(reversed(us),reversed(vs),reversed(hws))]
            band=[(p(a),p(b)) for a,b in band]
            d.polygon(band, fill=SYSTEM+(70,))
            d.line(band+[band[0]], fill=SYSTEM, width=int(3*S), joint="curve")

        # 앞차
        for key, primary in (("lead2", False), ("lead", True)):
            L = pkt.get(key)
            if not L: continue
            dist, y, a = L
            if dist < MIN_X: continue
            if not primary and dist > 60: continue
            u, v = sx(dist,y), sy(dist)
            wpx = max(26.0 if primary else 20.0, min(200.0, sc(dist,1.8))); hpx = wpx*0.62
            col = DANGER if a < -0.5 else ROAD
            half, top = wpx*0.5, v-hpx
            box=[(u-half,v),(u+half,v),(u+half*0.86,top),(u-half*0.86,top)]
            box=[(p(m),p(n)) for m,n in box]
            if primary:
                d.polygon(box, fill=col)
                lab=f"{dist:.0f}m"; text(d, lab, u-tw(lab,f_label)*0.5, top-10, f_label, col)
            else:
                d.line(box+[box[0]], fill=col, width=int(2*S), joint="curve")

        # 표지 / 방지턱
        cam, cdist = pkt.get("camera",0), pkt.get("cameraDist",0)
        limit, bump = pkt.get("limit",0), pkt.get("bumpDist",0)
        enforced = cam > 0 and cdist > 0
        val = cam if enforced else limit
        cx, cy = W-62.0, safeTop+42.0; ly = cy+58
        if val > 0:
            col = DANGER if enforced else ROAD
            wd = 7 if enforced else 4
            d.ellipse([p(cx-28),p(cy-28),p(cx+28),p(cy+28)], outline=col, width=int(wd*S))
            s=str(val); text(d, s, cx-tw(s,f_sign)*0.5, cy+11, f_sign, ROAD)
            if enforced:
                lab=("구간 " if pkt.get("cameraSection") else "")+f"{cdist}m"
                text(d, lab, min(cx-tw(lab,f_label)*0.5, W-16-tw(lab,f_label)), ly, f_label, ROAD); ly += 26
        if bump > 0:
            lab=f"방지턱 {bump}m"
            text(d, lab, min(cx-tw(lab,f_label)*0.5, W-16-tw(lab,f_label)), ly if val>0 else cy, f_label, CAUTION)

    # 자차
    ep = ego_path()
    d.line(ep+[ep[0]], fill=BLACK, width=int(8*S), joint="curve")
    d.polygon(ep, fill=SYSTEM)
    cx, cy = W*0.5, ego_cy(); hw, hl = EGO_W*0.5, EGO_L*0.5
    my, mw, mh = cy-hl*0.30, hw*0.20, hl*0.10
    d.rectangle([p(cx-hw-mw),p(my-mh),p(cx-hw*0.96),p(my+mh)], fill=SYSTEM)
    d.rectangle([p(cx+hw*0.96),p(my-mh),p(cx+hw+mw),p(my+mh)], fill=SYSTEM)
    for win in ([(cx-hw*0.62,cy-hl*0.10),(cx+hw*0.62,cy-hl*0.10),(cx+hw*0.46,cy-hl*0.60),(cx-hw*0.46,cy-hl*0.60)],
                [(cx-hw*0.60,cy+hl*0.24),(cx+hw*0.60,cy+hl*0.24),(cx+hw*0.52,cy+hl*0.62),(cx-hw*0.52,cy+hl*0.62)]):
        d.polygon([(p(a),p(b)) for a,b in win], fill=BLACK)

    # 좌상단
    base = safeTop + SZ_SPEED
    if stale:
        text(d, "신호 끊김", 16, safeTop+SZ_ALERT, f_alert, DANGER)
        text(d, f"{pkt.get('ago',3)}초 · 연결 대기", 16, safeTop+SZ_ALERT+26, f_label, DANGER)
    else:
        sp, lim = pkt.get("speed",0), pkt.get("limit",0)
        over = lim > 0 and sp > lim + 2
        col = CAUTION if over else ROAD
        num=str(sp)
        text(d, num, 16, base, f_speed, col)
        text(d, "km/h", 16+tw(num,f_speed)+8, base, f_unit, col)

    img.resize((W,H), Image.LANCZOS).save(out)

OUT="/home/claude/preview"; os.makedirs(OUT, exist_ok=True)
scenes = [
 ("p1-cruise",      dict(speed=62, limit=60, laneL=10, laneR=11, lead=(40,0.05,-0.1))),
 ("p2-lead-braking",dict(speed=62, limit=60, laneL=10, laneR=11, lead=(26,0,-2.4), lead2=(52,0.2,0))),
 ("p3-bsd-yellow",  dict(speed=62, limit=60, laneL=10, laneR=11, leftBsd=True, lead=(48,0,0))),
 ("p4-bsd-red",     dict(speed=62, limit=60, laneL=10, laneR=11, leftBsd=True, leftBlinker=True, lead=(48,0,0))),
 ("p5-camera",      dict(speed=74, limit=60, laneL=10, laneR=11, camera=50, cameraDist=120)),
 ("p6-section",     dict(speed=83, limit=80, laneL=10, laneR=11, camera=80, cameraDist=1000, cameraSection=True)),
 ("p7-bump",        dict(speed=34, limit=60, laneL=10, laneR=11, bumpDist=33, rightBsd=True, rightBlinker=True)),
 ("p8-lost",        dict(stale=True, ago=3)),
 ("p9-over-speed",  dict(speed=71, limit=60, laneL=21, laneR=11, curve=1.0, lead=(33,-0.6,0))),
]
for name, pkt in scenes:
    render(pkt, f"{OUT}/{name}.png")

sheet = Image.new("RGB", (W*3+40, H*3+40), (18,18,18))
for i,(name,_) in enumerate(scenes):
    sheet.paste(Image.open(f"{OUT}/{name}.png"), ((i%3)*(W+20), (i//3)*(H+20)))
sheet.save(f"{OUT}/sheet.png")
print("ok", len(scenes))
