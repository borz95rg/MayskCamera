package com.maysk.camera.camera.gl

internal object Shaders {
    const val VERTEX = """
attribute vec4 aPosition;
attribute vec4 aTexCoord;
uniform mat4 uTexMatrix;
varying vec2 vTex;
varying vec2 vPos;
void main() {
    gl_Position = aPosition;
    vTex = (uTexMatrix * aTexCoord).xy;
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
    float skin = skinMask(c);
    float smoothAmt = uDenoise * 0.65 + uBeauty * skin;
    if (smoothAmt > 0.01) {
        vec3 b = bilateral(c, 1.5 + uBeauty * 2.5);
        c = mix(c, b, clamp(smoothAmt, 0.0, 1.0));
    }

    // 2. Свет: осветление лица, экспозиция, подъём теней (ночь)
    c += skin * uFaceLight * 0.15;
    c *= uGain;
    if (uLift > 0.0) {
        c = pow(max(c, vec3(0.0)), vec3(1.0 - uLift * 0.4));
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

    // 4. LOG
    if (uLog > 0.0) {
        vec3 lg = log(1.0 + clamp(c, 0.0, 1.0) * 12.0) / log(13.0);
        lg = mix(vec3(luma(lg)), lg, 0.8) * 0.82 + 0.08;
        c = mix(c, lg, uLog);
    }

    // 5. Фильтр
    vec4 col = uColor * vec4(clamp(c, 0.0, 1.0), 1.0) + uOffset;
    c = col.rgb;
    c = c * (1.0 - uFade * 0.25) + uFade * 0.1;
    vec2 p = vPos - 0.5;
    p.x *= uAspect;
    float dist = length(p);
    c *= 1.0 - uVignette * smoothstep(0.35, 0.95, dist);

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
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
