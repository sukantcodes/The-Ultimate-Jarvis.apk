package com.jarvis.assistant.ui.glass

/**
 * Physically inspired iOS 26/27 Liquid Glass AGSL shaders (Android 13+ / API 33+).
 *
 * Key optical behaviors modeled after Apple's Liquid Glass material:
 *  - Smooth convex-meniscus Snell refraction near the rounded-rect perimeter (bends and compresses
 *    background detail into a crisp optical ring along the curved rim while keeping the interior clear).
 *  - 13-tap Golden-Angle disc blur that softens interior content without boxy grid artifacts, while
 *    keeping the refracted edge slightly crisper so bent light reads like real curved glass.
 *  - Subtle physically-weighted RGB chromatic dispersion strictly along high-curvature bezel normals
 *    (no artificial neon magenta/cyan outlines).
 *  - Adaptive vibrancy (chroma boost) + luminance-aware Fresnel tint so glass stays luminous over
 *    dark content and legible over bright content.
 *  - Dual directional Fresnel specular rim + interactive touch-reactive caustic highlight.
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
uniform float blur;
uniform float zoom;
uniform float isLight;
uniform float2 touchPos;
uniform float touchGlow;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float2 roundRectNormal(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    if (q.x > 0.0 && q.y > 0.0) {
        return normalize(q) * sign(p);
    } else if (q.x > q.y) {
        return float2(sign(p.x), 0.0);
    } else {
        return float2(0.0, sign(p.y));
    }
}

// 13-tap Golden-Angle disc Gaussian blur (eliminates 3x3 box-grid artifacts).
half4 sampleDiscBlur(float2 p, float blurR, float2 lo, float2 hi) {
    if (blurR <= 0.35) {
        return content.eval(clamp(p, lo, hi));
    }
    float r1 = blurR * 0.48;
    float r2 = blurR;
    half4 acc = content.eval(clamp(p, lo, hi)) * half(0.16);
    // Inner 4-tap rotated ring
    acc += content.eval(clamp(p + float2( r1,  0.0), lo, hi)) * half(0.09);
    acc += content.eval(clamp(p + float2(-r1,  0.0), lo, hi)) * half(0.09);
    acc += content.eval(clamp(p + float2( 0.0,  r1), lo, hi)) * half(0.09);
    acc += content.eval(clamp(p + float2( 0.0, -r1), lo, hi)) * half(0.09);
    // Outer 8-tap circular ring
    float d = r2 * 0.7071;
    acc += content.eval(clamp(p + float2( r2,  0.0), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2(-r2,  0.0), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2( 0.0,  r2), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2( 0.0, -r2), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2(  d,    d), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2( -d,    d), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2(  d,   -d), lo, hi)) * half(0.06);
    acc += content.eval(clamp(p + float2( -d,   -d), lo, hi)) * half(0.06);
    return acc;
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 p = coord - halfSize;
    float d = sdRoundRect(p, halfSize, radius);

    // Smooth anti-aliased shape mask
    float aa = 1.0 - smoothstep(-0.9, 0.65, d);
    if (aa <= 0.001) {
        return half4(0.0);
    }

    float2 n = roundRectNormal(p, halfSize, radius);

    // Physical convex meniscus depth profile:
    // distIn = 0 at outer glass wall, 1 at inner flat plateau
    float safeBezel = max(bezel, 2.0);
    float distIn = clamp(-d / safeBezel, 0.0, 1.0);

    // Convex curved surface slope (Snell-inspired lens profile: steepest right at the rim)
    float oneMinus = 1.0 - distIn;
    float meniscusSlope = oneMinus * sqrt(clamp(1.0 - distIn * distIn * 0.65, 0.0, 1.0));
    float bezelCurve = pow(oneMinus, 1.75);

    // Optical edge refraction vector: pulls & compresses outside detail inward like thick curved glass
    float2 refractVec = n * (meniscusSlope * strength);

    // Subtle interior lens dome magnification (keeps center feeling like real optical glass)
    float centerDome = 1.0 + (0.025 * (1.0 - bezelCurve));
    float effectiveZoom = max(zoom, 1.0) * centerDome;
    float2 baseCoord = halfSize + p / effectiveZoom;

    // Slightly crisper blur along the outer optical bevel so refracted pixels gleam cleanly
    float localBlur = blur * mix(0.55, 1.0, smoothstep(0.0, 0.65, distIn));

    float2 lo = float2(0.5);
    float2 hi = size - 0.5;

    // Subtle, realistic RGB dispersion concentrated strictly in the curved meniscus zone
    float dispScale = mix(0.0, 1.0, bezelCurve);
    float2 offR = refractVec * mix(1.0, chroma.x, dispScale * 0.55);
    float2 offG = refractVec * mix(1.0, chroma.y, dispScale * 0.55);
    float2 offB = refractVec * mix(1.0, chroma.z, dispScale * 0.55);

    half4 cr = sampleDiscBlur(baseCoord - offR, localBlur, lo, hi);
    half4 cg = sampleDiscBlur(baseCoord - offG, localBlur, lo, hi);
    half4 cb = sampleDiscBlur(baseCoord - offB, localBlur, lo, hi);
    half3 col = half3(cr.r, cg.g, cb.b);

    // Apple-style Vibrancy: lift chroma cleanly without clipping highlights
    half luma = dot(col, half3(0.299, 0.587, 0.114));
    half satBoost = mix(half(1.36), half(1.22), half(isLight));
    col = clamp(mix(half3(luma), col, satBoost), half3(0.0), half3(1.0));

    // Adaptive glass body tint (luminance-aware so glass stays clear on dark & crisp on light)
    half adaptVeil = half(veil) * mix(half(0.75), half(1.25), half(isLight));
    half3 glassTint = mix(half3(0.95, 0.97, 1.0), half3(1.0, 1.0, 1.0), half(isLight));
    col = mix(col, glassTint, clamp(adaptVeil + half(bezelCurve * 0.06), half(0.0), half(0.42)));

    // Subtle inner depth shadow just inside the glass rim (gives physical thickness)
    float innerShadowBand = smoothstep(0.0, 0.28, distIn) * (1.0 - smoothstep(0.28, 0.85, distIn));
    float bottomWeight = clamp((p.y / max(halfSize.y, 1.0)) * 0.5 + 0.5, 0.0, 1.0);
    col *= half(1.0 - innerShadowBand * mix(0.08, 0.04, isLight) * (0.45 + 0.55 * bottomWeight));

    // Dual-layer Fresnel specular rim (sharp 1.5px glass edge + softer curved meniscus sheen)
    float sharpRim = exp(-max(-d, 0.0) * 0.72);
    float softRim = pow(oneMinus, 2.6);

    float2 lightDir = normalize(float2(-0.48, -0.88));
    float topSpec = pow(max(dot(n, lightDir), 0.0), 2.4);
    float botBounce = pow(max(dot(n, -lightDir), 0.0), 2.8) * 0.52;
    float omniFresnel = 0.22;

    float rimLight = sharpRim * (0.34 + 0.52 * topSpec + 0.24 * botBounce)
                   + softRim * (0.06 + 0.18 * topSpec + 0.09 * botBounce + omniFresnel * 0.08);
    col += half3(half(rimLight * mix(0.92, 1.05, isLight)));

    // Subtle top-surface overhead sky reflection
    float topSheen = (1.0 - smoothstep(-halfSize.y, -halfSize.y * 0.12, p.y)) * (1.0 - sharpRim * 0.4);
    col += half3(half(topSheen * mix(0.075, 0.14, isLight)));

    // Interactive touch-reactive internal caustic illumination (iOS 26/27 interactive glass glow)
    if (touchGlow > 0.005) {
        float touchDist = length(coord - touchPos);
        float glowRadius = max(size.y * 1.35, 72.0);
        float tFalloff = exp(-(touchDist * touchDist) / (glowRadius * glowRadius * 0.45));
        float rimBoost = 1.0 + sharpRim * 1.45 + softRim * 0.65;
        col += half3(half(tFalloff * touchGlow * 0.22 * rimBoost));
    }

    col = clamp(col, half3(0.0), half3(1.0));
    return half4(col * half(aa), half(aa));
}
"""

    /**
     * Dedicated moving-droplet Liquid Lens shader for GlassBubbleView (the sliding dock tab lens).
     * Creates true iOS 26/27 floating glass magnification over the underlying dock surface.
     */
    const val LENS_SRC = """
uniform shader content;
uniform float2 viewSize;
uniform float4 pillRect;
uniform float cornerR;
uniform float zoom;
uniform float strength;
uniform float3 tintColor;
uniform float tintAmount;
uniform float isLight;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float2 roundRectNormal(float2 p, float2 halfSize, float r) {
    float2 q = abs(p) - halfSize + r;
    if (q.x > 0.0 && q.y > 0.0) {
        return normalize(q) * sign(p);
    } else if (q.x > q.y) {
        return float2(sign(p.x), 0.0);
    } else {
        return float2(0.0, sign(p.y));
    }
}

half4 main(float2 coord) {
    float2 center = (pillRect.xy + pillRect.zw) * 0.5;
    float2 halfSize = max((pillRect.zw - pillRect.xy) * 0.5, float2(1.0));
    float r = min(cornerR, min(halfSize.x, halfSize.y));
    float2 p = coord - center;
    float d = sdRoundRect(p, halfSize, r);

    float aa = 1.0 - smoothstep(-0.85, 0.65, d);
    if (aa <= 0.001) {
        return half4(0.0);
    }

    float2 n = roundRectNormal(p, halfSize, r);
    float bezelW = max(min(halfSize.x, halfSize.y) * 0.58, 4.0);
    float distIn = clamp(-d / bezelW, 0.0, 1.0);
    float oneMinus = 1.0 - distIn;
    float curve = oneMinus * sqrt(clamp(1.0 - distIn * distIn * 0.55, 0.0, 1.0));

    // Magnifying water-drop lens coordinates with curved rim refraction
    float2 sampleBase = center + p / zoom;
    float2 refractOff = n * (curve * strength);

    float2 lo = float2(0.5);
    float2 hi = viewSize - 0.5;

    // Crisp micro-chromatic optical lens sampling
    half4 cr = content.eval(clamp(sampleBase - refractOff * 1.06, lo, hi));
    half4 cg = content.eval(clamp(sampleBase - refractOff, lo, hi));
    half4 cb = content.eval(clamp(sampleBase - refractOff * 0.94, lo, hi));
    half3 col = half3(cr.r, cg.g, cb.b);

    // Luminous lens lift + subtle vibrancy
    half luma = dot(col, half3(0.299, 0.587, 0.114));
    col = clamp(mix(half3(luma), col, half(1.28)), half3(0.0), half3(1.0));

    // Subtle glass pill brightness lift & optional accent tint
    half bodyVeil = mix(half(0.14), half(0.24), half(isLight));
    col = mix(col, half3(1.0), bodyVeil);
    if (tintAmount > 0.001) {
        col = mix(col, half3(tintColor), half(tintAmount));
    }

    // Crisp Fresnel specular edge + bottom internal caustic bounce
    float sharpRim = exp(-max(-d, 0.0) * 0.85);
    float softRim = pow(oneMinus, 2.3);
    float2 L = normalize(float2(-0.45, -0.89));
    float specTop = pow(max(dot(n, L), 0.0), 2.2);
    float specBot = pow(max(dot(n, -L), 0.0), 2.6) * 0.55;

    float rim = sharpRim * (0.42 + 0.55 * specTop + 0.28 * specBot)
              + softRim * (0.09 + 0.22 * specTop + 0.12 * specBot);
    col += half3(half(rim));

    // Top-left elliptical catch-light inside the lens
    float2 normP = p / halfSize;
    float catchDist = length((normP - float2(-0.28, -0.48)) * float2(1.15, 2.2));
    float catchLight = exp(-catchDist * catchDist * 3.2) * mix(0.22, 0.34, isLight);
    col += half3(half(catchLight));

    col = clamp(col, half3(0.0), half3(1.0));
    return half4(col * half(aa), half(aa));
}
"""
}
