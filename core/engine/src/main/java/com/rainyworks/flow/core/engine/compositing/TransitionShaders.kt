package com.rainyworks.flow.core.engine.compositing

import com.rainyworks.flow.core.model.TransitionCatalog

/**
 * GLSL for main-track transitions. `transition(uv, p, type)` blends `outgoing(at)` into
 * `incoming(at)` as progress `p` goes from 0 to 1; both return premultiplied color.
 */
internal object TransitionShaders {
    private val types = TransitionCatalog.all.map { it.type }

    fun index(type: String): Int = types.indexOf(type).coerceAtLeast(types.indexOf("dissolve"))

    private fun id(type: String) = "${types.indexOf(type)}.0"

    val FUNCTION: String = """
        float easeInOut(float t) { return t * t * (3.0 - 2.0 * t); }
        float tHash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
        vec4 opaque(vec3 c) { return vec4(c, 1.0); }

        vec4 transition(vec2 uv, float p, float type) {
            float e = easeInOut(p);
            if (type == ${id("fade.black")} || type == ${id("fade.white")}) {
                vec4 mid = type == ${id("fade.black")} ? opaque(vec3(0.0)) : opaque(vec3(1.0));
                return p < 0.5 ? mix(outgoing(uv), mid, easeInOut(p * 2.0))
                               : mix(mid, incoming(uv), easeInOut(p * 2.0 - 1.0));
            }
            if (type == ${id("slide.left")}) {
                return uv.x < 1.0 - e ? outgoing(uv + vec2(e, 0.0)) : incoming(uv - vec2(1.0 - e, 0.0));
            }
            if (type == ${id("slide.right")}) {
                return uv.x > e ? outgoing(uv - vec2(e, 0.0)) : incoming(uv + vec2(1.0 - e, 0.0));
            }
            if (type == ${id("slide.up")}) {
                return uv.y > e ? outgoing(uv - vec2(0.0, e)) : incoming(uv + vec2(0.0, 1.0 - e));
            }
            if (type == ${id("slide.down")}) {
                return uv.y < 1.0 - e ? outgoing(uv + vec2(0.0, e)) : incoming(uv - vec2(0.0, 1.0 - e));
            }
            if (type == ${id("wipe.left")}) {
                float edge = smoothstep(e - 0.02, e + 0.02, 1.0 - uv.x);
                return mix(incoming(uv), outgoing(uv), edge);
            }
            if (type == ${id("wipe.right")}) {
                float edge = smoothstep(e - 0.02, e + 0.02, uv.x);
                return mix(incoming(uv), outgoing(uv), edge);
            }
            if (type == ${id("zoom")}) {
                vec4 a = outgoing((uv - 0.5) / (1.0 + e * 1.5) + 0.5);
                vec4 b = incoming((uv - 0.5) / (0.6 + 0.4 * e) + 0.5);
                return mix(a, b, smoothstep(0.35, 0.65, p));
            }
            if (type == ${id("spin")}) {
                float angle = (p < 0.5 ? e : e - 1.0) * 3.14159265;
                float zoom = 1.0 + sin(p * 3.14159265) * 0.6;
                vec2 d = uv - 0.5;
                vec2 r = vec2(cos(angle) * d.x - sin(angle) * d.y, sin(angle) * d.x + cos(angle) * d.y) * zoom + 0.5;
                return p < 0.5 ? outgoing(r) : incoming(r);
            }
            if (type == ${id("glitch")}) {
                float strength = sin(p * 3.14159265);
                float t = floor(p * 24.0);
                float band = floor(uv.y * 18.0);
                float shift = (tHash(vec2(band, t)) - 0.5) * 0.25 * strength * step(0.5, tHash(vec2(t, band + 3.0)));
                vec2 q = uv + vec2(shift, 0.0);
                vec4 base = p < 0.5 ? outgoing(q) : incoming(q);
                vec4 split = p < 0.5 ? outgoing(q + vec2(0.02 * strength, 0.0)) : incoming(q + vec2(0.02 * strength, 0.0));
                return vec4(split.r, base.g, base.b, base.a);
            }
            if (type == ${id("flash")}) {
                float flash = 1.0 - abs(p * 2.0 - 1.0);
                vec4 c = p < 0.5 ? outgoing(uv) : incoming(uv);
                return mix(c, opaque(vec3(1.0)), easeInOut(flash));
            }
            if (type == ${id("iris")}) {
                // A circle opening from the center; aspect-correct so it stays round.
                vec2 d = (uv - 0.5) * vec2(uSize.x / uSize.y, 1.0);
                float radius = e * length(vec2(uSize.x / uSize.y, 1.0)) * 0.5;
                return mix(outgoing(uv), incoming(uv), 1.0 - smoothstep(radius - 0.01, radius + 0.01, length(d)));
            }
            if (type == ${id("blur")}) {
                // Blur up, swap at the peak, blur back down.
                float amount = sin(p * 3.14159265) * 0.03;
                vec4 a = vec4(0.0);
                vec4 b = vec4(0.0);
                for (int i = 0; i < 12; i++) {
                    float angle = float(i) * 2.399963;
                    vec2 o = vec2(cos(angle), sin(angle)) * amount * sqrt((float(i) + 0.5) / 12.0);
                    a += outgoing(uv + o);
                    b += incoming(uv + o);
                }
                return mix(a / 12.0, b / 12.0, smoothstep(0.4, 0.6, p));
            }
            if (type == ${id("pixelate")}) {
                // Squares grow to their largest at the midpoint, where the picture swaps.
                float cells = mix(120.0, 8.0, sin(p * 3.14159265));
                vec2 grid = vec2(cells * uSize.x / uSize.y, cells);
                vec2 q = (floor(uv * grid) + 0.5) / grid;
                return p < 0.5 ? outgoing(q) : incoming(q);
            }
            if (type == ${id("wipe.diagonal")}) {
                float edge = smoothstep(e * 1.2 - 0.04, e * 1.2 + 0.04, (uv.x + (1.0 - uv.y)) * 0.6);
                return mix(incoming(uv), outgoing(uv), edge);
            }
            if (type == ${id("heart")}) {
                // A heart growing from the center (classic implicit heart curve).
                vec2 h = (uv - vec2(0.5, 0.45)) * vec2(uSize.x / uSize.y, 1.0) / max(e * 1.6, 0.0001);
                h.y = -h.y;
                float f = pow(h.x * h.x + h.y * h.y - 0.09, 3.0) - h.x * h.x * h.y * h.y * h.y * 0.3;
                float inside = e >= 0.999 ? 1.0 : 1.0 - smoothstep(-0.0004, 0.0004, f);
                return mix(outgoing(uv), incoming(uv), inside);
            }
            return mix(outgoing(uv), incoming(uv), e);
        }
    """
}
