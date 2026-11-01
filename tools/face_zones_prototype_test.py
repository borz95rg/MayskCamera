import math, face_zones_prototype as Z
# синтетическое лицо: все нужные индексы получают позицию по шаблону
m=[[0.5,0.5,0] for _ in range(468)]
cx,cy=0.5,0.45
def ellipse(idx,c,rx,ry,start=0):
    n=len(idx)
    for k,i in enumerate(idx):
        a=2*math.pi*k/n+start; m[i]=[c[0]+rx*math.cos(a),c[1]+ry*math.sin(a),0]
ellipse(Z.OVAL,(cx,cy),0.22,0.19,-math.pi/2)
ellipse(Z.LIP_OUT,(cx,cy+0.10),0.07,0.040)
ellipse(Z.LIP_IN,(cx,cy+0.10),0.05,0.022)   # рот открыт
ellipse(Z.EYE_A,(cx-0.08,cy-0.04),0.035,0.012,math.pi)
ellipse(Z.EYE_B,(cx+0.08,cy-0.04),0.035,0.012)
ellipse(Z.BROW_A,(cx-0.08,cy-0.085),0.04,0.008)
ellipse(Z.BROW_B,(cx+0.08,cy-0.085),0.04,0.008)
# верхнее веко: точки 33..133 идут слева направо у «левого» глаза
for i,idx in enumerate(Z.LID_A):
    t=i/(len(Z.LID_A)-1); m[idx]=[cx-0.115+0.07*t, cy-0.04-0.012*math.sin(math.pi*t),0]
for i,idx in enumerate(Z.LID_B):
    t=i/(len(Z.LID_B)-1); m[idx]=[cx+0.115-0.07*t, cy-0.04-0.012*math.sin(math.pi*t),0]
m[10]=[cx,cy-0.19,0]; m[152]=[cx,cy+0.19,0]
for a,b,x,y in ((50,205,cx-0.12,cy+0.03),(280,425,cx+0.12,cy+0.03)): m[a]=[x,y-0.01,0]; m[b]=[x,y+0.01,0]
buf,an=Z.build([m])
g=lambda x,y,ch:buf[(int(y*Z.H)*Z.W+int(x*Z.W))*4+ch]
tests=[
 ("кожа на щеке",g(cx-0.12,cy+0.03,0)>200),
 ("кожа на лбу",g(cx,cy-0.13,0)>200),
 ("нет кожи в глазу",g(cx-0.08,cy-0.04,0)<40),
 ("нет кожи на брови",g(cx-0.08,cy-0.085,0)<90),
 ("нет кожи на губах",g(cx+0.05,cy+0.10,0)<40),
 ("нет кожи вне лица",g(0.05,0.05,0)==0),
 ("губы: кольцо",g(cx+0.06,cy+0.10,1)>200),
 ("губы: внутри рта пусто",g(cx,cy+0.10,1)<40),
 ("зубы внутри рта",g(cx,cy+0.10,3)>150),
 ("белки глаз",g(cx-0.08,cy-0.04,3)>150),
 ("тени над глазом",max(g(cx-0.08,cy-0.047-d,2) for d in (0.002,0.006,0.010,0.014))>150),
 ("тени не на щеке",g(cx-0.12,cy+0.03,2)==0),
]
ok=True
for n,r in tests:
    print(('OK  ' if r else 'FAIL'),n); ok&=r
print(an)
import sys; sys.exit(0 if ok else 1)
