import math
W,H=192,256
OVAL=[10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109]
LIP_OUT=[61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146]
LIP_IN=[78,191,80,81,82,13,312,311,310,415,308,324,318,402,317,14,87,178,88,95]
EYE_A=[33,246,161,160,159,158,157,173,133,155,154,153,145,144,163,7]
EYE_B=[263,466,388,387,386,385,384,398,362,382,381,380,374,373,390,249]
LID_A=[33,246,161,160,159,158,157,173,133]
LID_B=[263,466,388,387,386,385,384,398,362]
BROW_A=[46,53,52,65,55,107,66,105,63,70]
BROW_B=[276,283,282,295,285,336,296,334,293,300]

def pts(mesh,idx): return [(mesh[i][0]*W, mesh[i][1]*H) for i in idx]
def scaled(p,k):
    cx=sum(x for x,_ in p)/len(p); cy=sum(y for _,y in p)/len(p)
    return [(cx+(x-cx)*k, cy+(y-cy)*k) for x,y in p]

def fill(buf,ch,poly,val):
    n=len(poly)
    if n<3: return
    ymin=max(0,int(math.floor(min(y for _,y in poly)-0.5)))
    ymax=min(H-1,int(math.ceil(max(y for _,y in poly)-0.5)))
    for row in range(ymin,ymax+1):
        yc=row+0.5; xs=[]
        for i in range(n):
            x0,y0=poly[i]; x1,y1=poly[(i+1)%n]
            if (y0<=yc<y1) or (y1<=yc<y0):
                xs.append(x0+(yc-y0)/(y1-y0)*(x1-x0))
        xs.sort()
        for k in range(0,len(xs)-1,2):
            a=max(0,int(math.ceil(xs[k]-0.5))); b=min(W-1,int(math.ceil(xs[k+1]-0.5))-1)
            for x in range(a,b+1): buf[(row*W+x)*4+ch]=val

def blur(buf,ch,r,passes):
    for _ in range(passes):
        tmp=[0]*(W*H)
        for y in range(H):
            for x in range(W):
                s=0;c=0
                for dx in range(-r,r+1):
                    xx=x+dx
                    if 0<=xx<W: s+=buf[(y*W+xx)*4+ch]; c+=1
                tmp[y*W+x]=s//c
        for y in range(H):
            for x in range(W):
                s=0;c=0
                for dy in range(-r,r+1):
                    yy=y+dy
                    if 0<=yy<H: s+=tmp[yy*W+x]; c+=1
                buf[(y*W+x)*4+ch]=s//c

def build(meshes,primary=0):
    buf=bytearray(W*H*4); anchors=None
    for mi,m in enumerate(meshes):
        fill(buf,0,pts(m,OVAL),255)
        for loop,k in ((EYE_A,1.4),(EYE_B,1.4),(BROW_A,1.3),(BROW_B,1.3),(LIP_OUT,1.12)):
            fill(buf,0,scaled(pts(m,loop),k),0)
        fill(buf,1,pts(m,LIP_OUT),255); fill(buf,1,pts(m,LIP_IN),0)
        up=(m[10][0]-m[152][0], m[10][1]-m[152][1]); L=math.hypot(*up) or 1; up=(up[0]/L*W, up[1]/L*H)
        for lid,eye in ((LID_A,EYE_A),(LID_B,EYE_B)):
            p=pts(m,lid); ew=math.hypot(p[0][0]-p[-1][0],p[0][1]-p[-1][1])
            top=[]
            for i,(x,y) in enumerate(p):
                t=i/(len(p)-1); lift=ew*0.42*math.sin(math.pi*t)
                top.append((x+up[0]/math.hypot(*up)*lift, y+up[1]/math.hypot(*up)*lift))
            fill(buf,2,p+top[::-1],255)
        if mi==primary:
            for loop in (EYE_A,EYE_B): fill(buf,3,scaled(pts(m,loop),0.9),255)
            fill(buf,3,scaled(pts(m,LIP_IN),0.92),255)
            av=lambda a,b:((m[a][0]+m[b][0])/2,(m[a][1]+m[b][1])/2)
            fh=math.hypot((m[10][0]-m[152][0])*0.75,m[10][1]-m[152][1])
            c1=[av(50,205),av(280,425)]
            anchors=dict(mouth=av(13,14),eyeL=av(33,133),eyeR=av(263,362),cheekL=c1[0],cheekR=c1[1],radius=fh*0.15)
    blur(buf,0,1,2); blur(buf,1,1,1); blur(buf,2,1,2); blur(buf,3,1,1)
    return buf,anchors
