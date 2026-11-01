package com.maysk.camera.camera.gl

internal object Shaders {
    const val VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
uniform mat4 uOvMatrix;
uniform mat4 uBgMat;
uniform mat4 uZoneMat;
varying vec2 vZone;
varying vec2 vTex;
varying vec2 vPos;
varying vec2 vOv;
varying vec4 vBg;
void main() {
    vBg = uBgMat * aTexCoord;
    vZone = (uZoneMat * aTexCoord).zw;
    gl_Position = aPosition;
    vTex = (uTexMatrix * aTexCoord).xy;
    vOv = (uOvMatrix * aTexCoord).xy;
    vPos = aPosition.xy * 0.5 + 0.5;
}
"""

    /**
     * Единый шейдер обработки кадра:
     *  1) AI шумоподавление + сглаживание кожи (билатеральный фильтр по маске кожи YCbCr)
     *  2) осветление лица / экспозиция / ночной подъём теней
     *  3) резкость (unsharp mask)
     *  4) LOG-профиль
     *  5) цветовая матрица фильтра, выцветание, виньетка
     *  6) эффекты (боке, лучи, глитч, плёнка, зерно, радуга, дождь, снег)
     */
    const val FRAGMENT = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform samplerExternalOES uTex;
varying vec2 vTex;
varying vec2 vPos;
uniform vec2 uTexel;
uniform mat4 uColor;
uniform vec4 uOffset;
uniform sampler2D uLut;
uniform sampler2D uOv;
uniform float uOvOn;
varying vec2 vOv;
uniform float uBgOn;
uniform sampler2D uBgMask;
uniform sampler2D uBgTex;
varying vec4 vBg;
// зоны лица (R кожа, G губы, B веки, A белки/зубы) и макияж
varying vec2 vZone;
uniform sampler2D uZone;
uniform float uZoneOn;
uniform vec2 uZoneShift;
uniform vec4 uMkA;     // помада, блеск, зубы, белки глаз
uniform vec4 uMkB;     // тени, румяна, осветление
uniform vec3 uLipCol;
uniform vec3 uShCol;
uniform vec3 uBlCol;
uniform vec4 uFaceA;   // рот xy, глаз A xy
uniform vec4 uFaceB;   // глаз B xy, щека A xy
uniform vec4 uFaceC;   // щека B xy, радиус румян
uniform float uLutMix;
uniform float uGrain;
uniform float uLeak;
uniform float uSkinTone;
uniform float uWarmSat;
uniform vec4 uFocus;
uniform float uBeauty;
uniform float uDenoise;
uniform float uFaceLight;
uniform float uSharp;
uniform float uGain;
uniform float uLift;
uniform float uVignette;
uniform float uFade;
uniform float uLog;
uniform float uTime;
uniform float uAspect;
uniform int uEffect;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }

float skinMask(vec3 c) {
    float cb = 0.5 - 0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b;
    float cr = 0.5 + 0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b;
    float m1 = smoothstep(0.50, 0.54, cr) * (1.0 - smoothstep(0.66, 0.70, cr));
    float m2 = smoothstep(0.28, 0.32, cb) * (1.0 - smoothstep(0.50, 0.54, cb));
    return m1 * m2;
}

vec3 bilateral(vec3 c, float radius) {
    vec3 sum = c;
    float wsum = 1.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec2 dir = vec2(cos(a), sin(a)) * uTexel;
        vec3 s1 = texture2D(uTex, vTex + dir * radius).rgb;
        vec3 s2 = texture2D(uTex, vTex + dir * radius * 2.2).rgb;
        vec3 d1 = s1 - c;
        vec3 d2 = s2 - c;
        float w1 = exp(-dot(d1, d1) * 60.0);
        float w2 = exp(-dot(d2, d2) * 60.0) * 0.7;
        sum += s1 * w1 + s2 * w2;
        wsum += w1 + w2;
    }
    return sum / wsum;
}

vec3 hue(float h) {
    return clamp(abs(mod(h * 6.0 + vec3(0.0, 4.0, 2.0), 6.0) - 3.0) - 1.0, 0.0, 1.0);
}

void main() {
    vec3 c = texture2D(uTex, vTex).rgb;

    // 1. Шумоподавление и кожа
    // зоны лица: геометрия сетки вместо цвета — глаза, брови и губы не сглаживаются
    vec4 z = vec4(0.0);
    float skin = skinMask(c);
    if (uZoneOn > 0.5) {
        z = texture2D(uZone, vZone - uZoneShift);
        skin = z.r * (0.55 + 0.45 * skin);
    }
    float dnAmt = clamp(uDenoise * 0.65, 0.0, 1.0);
    float skAmt = clamp(uBeauty * skin, 0.0, 1.0);
    if (dnAmt + skAmt > 0.01) {
        vec3 b = bilateral(c, 1.5 + uBeauty * 2.5);
        // кожа: возвращаем часть мелкой детали (поры), тёмные пятна гасим сильнее
        vec3 r = c - b;
        float spot = smoothstep(0.02, 0.07, -luma(r));
        vec3 keepD = mix(vec3(0.30), vec3(0.08), spot) * uZoneOn;
        c = mix(c, b, dnAmt);
        c = mix(c, b + r * keepD, skAmt);
    }

    // 2. Свет: осветление лица, экспозиция, подъём теней (ночь)
    c += skin * uFaceLight * 0.15;
    c *= uGain;
    if (uLift > 0.0) {
        // подъём ТОЛЬКО теней: яркие места и огни не трогаем (без умножения всего кадра)
        float l0 = max(luma(c), 0.002);
        float t = 1.0 - clamp(l0 / 0.55, 0.0, 1.0);
        float l1 = l0 + uLift * 0.5 * l0 * t * t * 3.0;
        c *= min(l1 / l0, 1.0 + uLift * 1.6);
    }

    // 3. Резкость
    if (uSharp > 0.01) {
        vec3 n = texture2D(uTex, vTex + vec2(uTexel.x, 0.0)).rgb
               + texture2D(uTex, vTex - vec2(uTexel.x, 0.0)).rgb
               + texture2D(uTex, vTex + vec2(0.0, uTexel.y)).rgb
               + texture2D(uTex, vTex - vec2(0.0, uTexel.y)).rgb;
        vec3 orig = texture2D(uTex, vTex).rgb;
        c += (orig - n * 0.25) * uSharp * 1.5;
    }

    // 3b. Макияж по зонам лица (светотень и текстура губ/кожи сохраняются)
    if (uZoneOn > 0.5) {
        vec2 zc = vZone - uZoneShift;
        float l0 = luma(c);
        // осветление кожи без пересвета бликов
        c += z.r * uMkB.z * 0.10 * (1.0 - l0 * 0.6);
        // румяна: мягкое пятно на скулах, только по коже
        if (uMkB.y > 0.01) {
            vec2 da = (zc - uFaceB.zw) * vec2(0.75, 1.0);
            vec2 db = (zc - uFaceC.xy) * vec2(0.75, 1.0);
            float rr = max(uFaceC.z * uFaceC.z, 1e-5);
            float bl = clamp(exp(-dot(da, da) / rr) + exp(-dot(db, db) / rr), 0.0, 1.0) * z.r * uMkB.y;
            c = mix(c, c * 0.55 + uBlCol * (0.35 + 0.5 * l0), bl * 0.75);
        }
        // тени для век
        if (uMkB.x > 0.01 && z.b > 0.01) {
            c = mix(c, uShCol * (0.40 + 0.9 * l0), z.b * uMkB.x * 0.7);
        }
        // помада и блеск
        if (z.g > 0.01 && (uMkA.x > 0.01 || uMkA.y > 0.01)) {
            c = mix(c, uLipCol * (0.30 + 1.15 * l0), z.g * uMkA.x * 0.8);
            float glo = smoothstep(0.45, 0.85, luma(c));
            c += z.g * uMkA.y * (0.05 + 0.35 * glo * glo);
        }
        // зубы и белки глаз: убираем желтизну только у светлых малонасыщенных пикселей
        if (z.a > 0.01 && (uMkA.z > 0.01 || uMkA.w > 0.01)) {
            float dm = distance(zc, uFaceA.xy);
            float de = min(distance(zc, uFaceA.zw), distance(zc, uFaceB.xy));
            float amt = (dm < de) ? uMkA.z : uMkA.w;
            float mx = max(c.r, max(c.g, c.b));
            float mn = min(c.r, min(c.g, c.b));
            float sat = (mx - mn) / max(mx, 0.001);
            float wt = (1.0 - smoothstep(0.22, 0.50, sat)) * smoothstep(0.30, 0.55, mx) * z.a * amt;
            c = mix(c, vec3(luma(c) * 1.06 + 0.02), wt * 0.8);
            c += wt * 0.04;
        }
    }

    // 4. LOG
    if (uLog > 0.0) {
        vec3 lg = log(1.0 + clamp(c, 0.0, 1.0) * 12.0) / log(13.0);
        lg = mix(vec3(luma(lg)), lg, 0.8) * 0.82 + 0.08;
        c = mix(c, lg, uLog);
    }

    // 4b. Живая замена фона: маска человека ML Kit (xy — фон в координатах экрана, zw — маска в кадре анализа)
    if (uBgOn > 0.5) {
        float pm = smoothstep(0.3, 0.7, texture2D(uBgMask, vBg.zw).r);
        vec3 bgc;
        if (uBgOn > 1.5) {
            bgc = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                bgc += texture2D(uTex, vTex + vec2(cos(a), sin(a)) * uTexel * 16.0).rgb;
                bgc += texture2D(uTex, vTex + vec2(cos(a + 0.39), sin(a + 0.39)) * uTexel * 30.0).rgb;
            }
            bgc /= 16.0;
        } else {
            bgc = texture2D(uBgTex, vBg.xy).rgb;
        }
        c = mix(bgc, c, pm);
    }

    // 5. Фильтр
    vec4 col = uColor * vec4(clamp(c, 0.0, 1.0), 1.0) + uOffset;
    c = clamp(col.rgb, 0.0, 1.0);
    if (uLutMix > 0.0) {
        // тональная кривая фильтра (LUT 256x1: R,G,B в каналах текстуры)
        vec3 q = c * (255.0 / 256.0) + 0.5 / 256.0;
        vec3 lc = vec3(texture2D(uLut, vec2(q.r, 0.5)).r, texture2D(uLut, vec2(q.g, 0.5)).g, texture2D(uLut, vec2(q.b, 0.5)).b);
        c = mix(c, lc, uLutMix);
    }
    // 5b. Приёмы фильтра (та же математика в Filters.applyExtras для фото)
    if (uSkinTone > 0.0) {
        float s = skinMask(c) * uSkinTone;
        float o = uSkinTone - s;
        c += vec3(s * 0.05 - o * 0.02, s * 0.015, -s * 0.03 + o * 0.03);
    }
    if (uWarmSat > 0.0) {
        float l = luma(c);
        float mx = max(c.r, max(c.g, c.b));
        float mn = min(c.r, min(c.g, c.b));
        float warm = clamp((c.r - c.b) * 2.5, 0.0, 1.0) * clamp((mx - mn) * 4.0, 0.0, 1.0);
        c = vec3(l) + (c - vec3(l)) * (1.0 + 0.6 * uWarmSat * warm);
        c.r += 0.03 * uWarmSat * warm;
    }
    c = clamp(c, 0.0, 1.0);
    // портрет: мягче фон вокруг лица (uFocus = центр, радиус, сила); фильтр человека сохраняется
    if (uFocus.w > 0.0) {
        vec2 fp = (vPos - uFocus.xy) * vec2(uAspect, 1.0);
        float fm = smoothstep(uFocus.z, uFocus.z * 2.2, length(fp)) * uFocus.w;
        if (fm > 0.01) {
            vec3 bl = vec3(0.0);
            for (int i = 0; i < 8; i++) {
                float a = float(i) * 0.785398;
                bl += texture2D(uTex, vTex + vec2(cos(a), sin(a)) * uTexel * 9.0).rgb;
                bl += texture2D(uTex, vTex + vec2(cos(a + 0.39), sin(a + 0.39)) * uTexel * 18.0).rgb;
            }
            bl /= 16.0;
            vec3 bf = clamp((uColor * vec4(bl, 1.0) + uOffset).rgb, 0.0, 1.0);
            if (uLutMix > 0.0) {
                vec3 q2 = bf * (255.0 / 256.0) + 0.5 / 256.0;
                bf = mix(bf, vec3(texture2D(uLut, vec2(q2.r, 0.5)).r, texture2D(uLut, vec2(q2.g, 0.5)).g, texture2D(uLut, vec2(q2.b, 0.5)).b), uLutMix);
            }
            c = mix(c, bf, fm);
        }
    }
    c = c * (1.0 - uFade * 0.25) + uFade * 0.1;
    vec2 p = vPos - 0.5;
    p.x *= uAspect;
    float dist = length(p);
    c *= 1.0 - uVignette * smoothstep(0.35, 0.95, dist);
    if (uLeak > 0.0) {
        // тёплая засветка из левого верхнего угла и по правому краю (screen-смешивание)
        vec2 d = vec2(vPos.x * uAspect, vPos.y - 1.0);
        float m1 = 1.0 - clamp(length(d) / 0.75, 0.0, 1.0);
        float m2 = clamp((vPos.x - 0.82) / 0.18, 0.0, 1.0);
        float a = clamp((m1 * m1 * 0.9 + m2 * m2 * 0.45) * uLeak, 0.0, 1.0);
        c = vec3(1.0) - (vec3(1.0) - c) * (vec3(1.0) - vec3(1.0, 0.55, 0.25) * a);
    }
    if (uGrain > 0.0) {
        // зерно меняется каждый кадр, как на плёнке
        float n = rand(floor(gl_FragCoord.xy / 1.5) + vec2(fract(uTime * 7.13) * 91.0, fract(uTime * 3.7) * 57.0)) - 0.5;
        c += n * uGrain * 0.12 * (1.0 - 0.5 * luma(c));
    }

    // 6. Эффекты
    if (uEffect == 1) {
        vec3 blur = vec3(0.0);
        for (int i = 0; i < 8; i++) {
            float a = float(i) * 0.785398;
            blur += texture2D(uTex, vTex + vec2(cos(a), sin(a)) * uTexel * 14.0).rgb;
        }
        blur /= 8.0;
        c = mix(c, (uColor * vec4(blur, 1.0) + uOffset).rgb, smoothstep(0.22, 0.55, dist));
    } else if (uEffect == 2) {
        vec2 uv = vPos * vec2(6.0 * uAspect, 6.0);
        vec2 cell = floor(uv);
        vec2 f = fract(uv);
        vec2 ctr = vec2(rand(cell), rand(cell + 3.1));
        float r = 0.15 + 0.25 * rand(cell + 7.7);
        float circle = smoothstep(r, r - 0.06, length(f - ctr)) * step(0.55, rand(cell + 1.9));
        vec3 tint = mix(vec3(1.0, 0.75, 0.45), vec3(1.0, 0.4, 0.6), rand(cell + 5.3));
        c += circle * tint * 0.35 * smoothstep(0.15, 0.5, dist);
    } else if (uEffect == 3) {
        vec2 q = vPos - vec2(-0.1, 1.1);
        float ang = atan(q.y, q.x);
        float rays = pow(sin(ang * 18.0 + uTime * 0.4) * 0.5 + 0.5, 6.0);
        float fall = clamp(1.0 - length(q) * 0.75, 0.0, 1.0);
        c += rays * fall * vec3(1.0, 0.88, 0.65) * 0.45;
    } else if (uEffect == 4) {
        float band = step(0.92, rand(vec2(floor(vPos.y * 40.0), floor(uTime * 8.0))));
        float shift = (0.006 + band * 0.03) * sin(uTime * 3.0);
        float rr = texture2D(uTex, vTex + vec2(shift, 0.0)).r;
        float bb = texture2D(uTex, vTex - vec2(shift, 0.0)).b;
        c = vec3(mix(c.r, rr, 0.8), c.g, mix(c.b, bb, 0.8));
        c *= 0.92 + 0.08 * sin(vPos.y * 600.0);
    } else if (uEffect == 5) {
        float g = rand(vPos * 731.0 + fract(uTime)) - 0.5;
        c = c * vec3(1.05, 1.0, 0.9) * 0.92 + 0.05 + g * 0.07;
        c *= 1.0 - 0.5 * smoothstep(0.4, 0.95, dist);
    } else if (uEffect == 6) {
        c += (rand(vPos * 913.0 + fract(uTime)) - 0.5) * 0.12;
    } else if (uEffect == 7) {
        float h = fract(vPos.x * 0.6 + vPos.y * 0.4 + uTime * 0.03);
        c = mix(c, c * 0.7 + hue(h) * 0.45, 0.25);
    } else if (uEffect == 8) {
        float colm = floor(vPos.x * 90.0 * uAspect);
        float spd = 1.2 + rand(vec2(colm, 1.0));
        float y = fract(vPos.y * 2.0 + uTime * spd + rand(vec2(colm, 2.0)));
        float streak = smoothstep(0.0, 0.01, y) * (1.0 - smoothstep(0.01, 0.09, y)) * step(0.55, rand(vec2(colm, 3.0)));
        c = c * 0.85 + vec3(0.75, 0.8, 0.9) * streak * 0.55;
    } else if (uEffect == 9) {
        for (int l = 0; l < 3; l++) {
            float fl = float(l);
            float sc = 10.0 + fl * 8.0;
            vec2 uv = vPos * vec2(sc * uAspect, sc);
            uv.y += uTime * (0.8 + fl * 0.4);
            uv.x += sin(uTime * 0.7 + uv.y * 0.3) * 0.3;
            vec2 cell = floor(uv);
            vec2 f = fract(uv);
            vec2 ctr = vec2(rand(cell + fl), rand(cell + fl + 4.2));
            float flake = smoothstep(0.09 - fl * 0.02, 0.0, length(f - ctr)) * step(0.65, rand(cell + fl + 9.1));
            c = mix(c, vec3(1.0), flake * 0.9);
        }
    } else if (uEffect == 10) {
        // конфетти
        for (int l = 0; l < 2; l++) {
            float fl = float(l);
            float sc = 9.0 + fl * 6.0;
            vec2 uv = vPos * vec2(sc * uAspect, sc);
            uv.y += uTime * (0.6 + fl * 0.3);
            vec2 cell = floor(uv);
            vec2 f = fract(uv) - vec2(rand(cell + fl), rand(cell + 2.7 + fl));
            float ang = uTime * 3.0 + rand(cell) * 6.28;
            vec2 r = vec2(f.x * cos(ang) - f.y * sin(ang), f.x * sin(ang) + f.y * cos(ang));
            float piece = step(abs(r.x), 0.07) * step(abs(r.y), 0.035) * step(0.55, rand(cell + 5.0 + fl));
            c = mix(c, hue(rand(cell + 9.0)), piece);
        }
    } else if (uEffect == 11) {
        // звёзды: мерцание
        vec2 uv = vPos * vec2(14.0 * uAspect, 14.0);
        vec2 cell = floor(uv);
        vec2 f = fract(uv) - vec2(rand(cell), rand(cell + 1.3));
        float tw = 0.5 + 0.5 * sin(uTime * 3.0 + rand(cell) * 10.0);
        float star = (0.004 / (abs(f.x * f.y) + 0.002)) * smoothstep(0.25, 0.0, length(f)) * step(0.82, rand(cell + 4.0)) * tw;
        c += vec3(1.0, 0.95, 0.8) * min(star, 1.0) * 0.8;
    } else if (uEffect == 12) {
        // листопад
        vec2 uv = vPos * vec2(7.0 * uAspect, 7.0);
        uv.y += uTime * 0.35;
        uv.x += sin(uTime + uv.y * 1.5) * 0.25;
        vec2 cell = floor(uv);
        vec2 f = fract(uv) - vec2(rand(cell), rand(cell + 3.3));
        float leaf = smoothstep(0.12, 0.08, length(f * vec2(1.0, 2.2))) * step(0.6, rand(cell + 8.0));
        vec3 lc = mix(vec3(0.9, 0.45, 0.1), vec3(0.85, 0.2, 0.1), rand(cell + 1.0));
        c = mix(c, lc, leaf * 0.9);
    } else if (uEffect == 13) {
        // салют: вспышки искр
        float burst = floor(uTime * 0.7);
        vec2 ctr = vec2(0.2 + 0.6 * rand(vec2(burst, 1.0)), 0.55 + 0.3 * rand(vec2(burst, 2.0)));
        float age = fract(uTime * 0.7);
        vec2 q = (vPos - ctr) * vec2(uAspect, 1.0);
        float ang = atan(q.y, q.x);
        float ray = pow(abs(sin(ang * 12.0)), 30.0);
        float ring = smoothstep(0.03, 0.0, abs(length(q) - age * 0.35));
        vec3 col = hue(rand(vec2(burst, 3.0)));
        c += col * ring * (0.4 + ray) * (1.0 - age) * 1.4;
    } else if (uEffect == 14) {
        // огонь снизу кадра
        float y = 1.0 - vPos.y;
        float n = rand(vec2(floor(vPos.x * 40.0), floor(uTime * 12.0)));
        float flame = smoothstep(0.35 + n * 0.12, 0.0, y) * (0.6 + 0.4 * sin(uTime * 8.0 + vPos.x * 20.0));
        c = c + vec3(1.0, 0.45, 0.05) * flame * 0.8 + vec3(1.0, 0.85, 0.3) * flame * flame * 0.4;
    } else if (uEffect == 15) {
        // дым/туман
        float s1 = sin(vPos.x * 6.0 + uTime * 0.4) * sin(vPos.y * 5.0 - uTime * 0.3);
        float s2 = sin(vPos.x * 11.0 - uTime * 0.6) * sin(vPos.y * 9.0 + uTime * 0.5);
        float fog = clamp(0.35 + 0.25 * s1 + 0.15 * s2, 0.0, 1.0) * smoothstep(0.0, 0.7, 1.0 - vPos.y);
        c = mix(c, vec3(0.85), fog * 0.45);
    }

    // 7. Рамка, «вшитая» в видео (премультиплицированная альфа)
    if (uOvOn > 0.5 && vOv.x >= 0.0 && vOv.x <= 1.0 && vOv.y >= 0.0 && vOv.y <= 1.0) {
        vec4 o = texture2D(uOv, vec2(vOv.x, 1.0 - vOv.y));
        c = clamp(c, 0.0, 1.0) * (1.0 - o.a) + o.rgb;
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""

    /** Тот же шейдер, но кадр читается из обычной текстуры (после временного шумоподавления). */
    val FRAGMENT_2D: String = FRAGMENT
        .replace("#extension GL_OES_EGL_image_external : require", "")
        .replace("uniform samplerExternalOES uTex;", "uniform sampler2D uTex;")

    const val TEMPORAL_VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vUv;
void main() {
    gl_Position = aPosition;
    vUv = aTexCoord.xy;
}
"""

    /**
     * Временное шумоподавление для ночного видео: смешиваем текущий кадр с предыдущим
     * результатом, но только там, где картинка не изменилась (движение не «мажется»).
     */
    const val TEMPORAL_FRAGMENT = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uCur;
uniform sampler2D uPrev;
uniform float uStrength;
uniform vec2 uTexel;
varying vec2 vUv;
void main() {
    vec3 cur = texture2D(uCur, vUv).rgb;
    vec3 prev = texture2D(uPrev, vUv).rgb;
    // Разницу оцениваем по локально сглаженному текущему кадру: шум сенсора
    // меньше похож на движение, а реальные перемещения берутся из текущего кадра.
    vec3 curB = (cur * 2.0 + texture2D(uCur, vUv + vec2(uTexel.x, 0.0)).rgb + texture2D(uCur, vUv - vec2(uTexel.x, 0.0)).rgb
               + texture2D(uCur, vUv + vec2(0.0, uTexel.y)).rgb + texture2D(uCur, vUv - vec2(0.0, uTexel.y)).rgb) / 6.0;
    float d = abs(dot(curB - prev, vec3(0.299, 0.587, 0.114)));
    float lum = dot(cur, vec3(0.299, 0.587, 0.114));
    // в тенях шум сильнее — порог выше
    float th = mix(0.035, 0.018, smoothstep(0.1, 0.5, lum));
    float w = uStrength * (1.0 - smoothstep(th, th * 2.5, d));
    gl_FragColor = vec4(mix(cur, prev, w), 1.0);
}
"""
}
