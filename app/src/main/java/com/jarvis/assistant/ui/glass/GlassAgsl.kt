package com.jarvis.assistant.ui.glass

/**
 * AGSL (Android 13+). Same recipe as ReactBits Glass Surface:
 * displace the backdrop near the rounded-rect edge, once per colour channel,
 * with the channels scaled by (distortionScale + offset). Then add a specular rim.
 */
internal object GlassAgsl {
    const val SRC = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float3 chroma;
uniform float veil;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float d = sdRoundRect(p, halfSize, radius);

    // outward normal of the rounded rect
    float2 q = abs(p) - halfSize + radius;
    float2 n;
    if (q.x > 0.0 && q.y > 0.0) {
        n = normalize(q) * sign(p);
    } else if (q.x > q.y) {
        n = float2(sign(p.x), 0.0);
    } else {
        n = float2(0.0, sign(p.y));
    }

    float depth = clamp(-d / bezel, 0.0, 1.0);
    float edge = pow(1.0 - depth, 2.0);
    float2 off = n * edge * strength;

    float2 lo = float2(0.0);
    float2 hi = size - 1.0;
    half4 cr = content.eval(clamp(coord + off * chroma.x, lo, hi));
    half4 cg = content.eval(clamp(coord + off * chroma.y, lo, hi));
    half4 cb = content.eval(clamp(coord + off * chroma.z, lo, hi));
    half3 col = half3(cr.r, cg.g, cb.b);

    // saturation lift + frosted veil
    half l = dot(col, half3(0.299, 0.587, 0.114));
    col = mix(half3(l), col, half(1.28));
    col = mix(col, half3(1.0), half(veil));

    // specular: strong top-left, softer bottom-right bounce
    float2 L = normalize(float2(-0.55, -0.83));
    float spec = pow(max(dot(n, L), 0.0), 2.0) + 0.45 * pow(max(dot(n, -L), 0.0), 2.0);
    col += half3(spec * edge * 0.5);

    float aa = 1.0 - smoothstep(-1.0, 0.5, d);
    return half4(col * aa, aa);
}
"""
}
