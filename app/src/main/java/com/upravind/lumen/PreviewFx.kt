package com.upravind.lumen

import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Live preview version of a look: the colour matrix plus the effects that can run on the GPU every frame
 * (vignette, grain, light leaks, pixel blocks, CRT scanlines, frosted blur, neon edges).
 * Needs Android 13+ (AGSL) for the extra effects; older phones get the colour matrix only.
 */
object PreviewFx {
    private const val AGSL = """
uniform shader content;
uniform float2 size;
uniform float vig;
uniform float scan;
uniform float pix;
uniform float grain;
uniform float leak;
uniform float neon;
uniform float t;
float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }
float luma(half3 c) { return dot(c, half3(0.299, 0.587, 0.114)); }
half3 blob(half3 c, float2 uv, float2 center, float r, half3 color, float a) {
    float k = (1.0 - clamp(distance(uv, center) / r, 0.0, 1.0)) * a;
    return 1.0 - (1.0 - c) * (1.0 - color * k);
}
half4 main(float2 p) {
    float2 q = p;
    if (pix > 0.5) { q = (floor(p / pix) + 0.5) * pix; }
    half4 c = content.eval(q);
    float2 uv = p / size;
    if (neon > 0.5) {
        float l0 = luma(c.rgb);
        float l1 = luma(content.eval(p + float2(3.0, 0.0)).rgb);
        float l2 = luma(content.eval(p + float2(0.0, 3.0)).rgb);
        float e = clamp((abs(l0 - l1) + abs(l0 - l2)) * 6.0, 0.0, 1.0);
        c.rgb = c.rgb * 0.45 + half3(0.15, 1.0, 0.4) * e;
    }
    if (leak > 0.5 && leak < 1.5) {
        c.rgb = blob(c.rgb, uv, float2(0.0, 0.85), 0.75, half3(1.0, 0.45, 0.12), 0.85);
        c.rgb = blob(c.rgb, uv, float2(1.0, 0.1), 0.5, half3(1.0, 0.2, 0.1), 0.55);
    } else if (leak > 1.5 && leak < 2.5) {
        c.rgb = blob(c.rgb, uv, float2(-0.1, 0.5), 0.9, half3(1.0, 0.78, 0.3), 0.8);
    } else if (leak > 2.5) {
        c.rgb = blob(c.rgb, uv, float2(1.0, 0.9), 0.8, half3(0.9, 0.2, 0.7), 0.8);
        c.rgb = blob(c.rgb, uv, float2(0.0, 0.1), 0.5, half3(0.4, 0.3, 1.0), 0.5);
    }
    if (vig > 0.0) {
        float d = distance(uv, float2(0.5, 0.5));
        c.rgb *= 1.0 - smoothstep(0.30, 0.85, d) * min(0.85, vig * 0.55);
    }
    if (scan > 0.5) {
        float line = mod(floor(p.y / 3.0), 2.0);
        c.rgb *= mix(1.0, 0.55, line);
    }
    if (grain > 0.0) {
        float n = hash(floor(p / 2.0) + t) - 0.5;
        c.rgb += half3(n * grain * 0.35);
    }
    return c;
}
"""

    fun apply(view: View, look: Look, strength: Float, extras: Extras, t: Float) {
        val cf = ColorMatrixColorFilter(look.matrix(strength))
        val spec = look.spec
        if (Build.VERSION.SDK_INT < 33) {
            view.setRenderEffect(null)
            view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = cf })
            return
        }
        val w = max(1f, view.width.toFloat()); val h = max(1f, view.height.toFloat())
        val vig = spec.vignette
        val scan = if (spec.fx == 3) 1f else 0f
        val pix = if (spec.fx == 1) max(6f, max(w, h) / 90f) else 0f
        val grain = min(1f, spec.grain + extras.grain)
        val neon = if (spec.fx == 4) 1f else 0f
        var effect = RenderEffect.createColorFilterEffect(cf)
        if (vig > 0f || scan > 0f || pix > 0f || grain > 0f || extras.leak > 0 || neon > 0f) {
            val shader = RuntimeShader(AGSL)
            shader.setFloatUniform("size", w, h)
            shader.setFloatUniform("vig", vig)
            shader.setFloatUniform("scan", scan)
            shader.setFloatUniform("pix", pix)
            shader.setFloatUniform("grain", grain)
            shader.setFloatUniform("leak", extras.leak.toFloat())
            shader.setFloatUniform("neon", neon)
            shader.setFloatUniform("t", t)
            effect = RenderEffect.createChainEffect(effect, RenderEffect.createRuntimeShaderEffect(shader, "content"))
        }
        if (spec.fx == 2) effect = RenderEffect.createChainEffect(effect, RenderEffect.createBlurEffect(16f, 16f, Shader.TileMode.CLAMP))
        view.setLayerType(View.LAYER_TYPE_NONE, null)
        view.setRenderEffect(effect)
    }
}
