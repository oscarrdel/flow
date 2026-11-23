package com.rainyworks.flow.core.engine.effects

/**
 * GLSL bodies for each effect type. Each body returns the color at `uv`; parameters arrive
 * in `uParams` in the order they are declared in the effect catalog.
 */
internal object EffectShaders {
    const val COMMON = """
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        float noise1(float x) {
            float i = floor(x);
            float f = fract(x);
            float u = f * f * (3.0 - 2.0 * f);
            return mix(hash(vec2(i, 0.0)), hash(vec2(i + 1.0, 0.0)), u) * 2.0 - 1.0;
        }
        vec4 sampleClamped(vec2 uv) { return texture2D(uInput, clamp(uv, vec2(0.0), vec2(1.0))); }
    """

    fun body(type: String): String = when (type) {
        // intensity
        "blur.gaussian" -> """
            float radius = uParams.x * 0.035 * min(uSize.x, uSize.y);
            vec2 px = radius / uSize;
            vec4 sum = vec4(0.0);
            float total = 0.0;
            for (int i = 0; i < 24; i++) {
                float a = float(i) * 2.399963;
                float r = sqrt((float(i) + 0.5) / 24.0);
                float w = exp(-2.0 * r * r);
                sum += sampleClamped(uv + vec2(cos(a), sin(a)) * r * px) * w;
                total += w;
            }
            return sum / total;
        """
        // intensity, angle
        "blur.motion" -> """
            float a = radians(uParams.y);
            vec2 dir = vec2(cos(a), sin(a)) * uParams.x * 0.06;
            vec4 sum = vec4(0.0);
            for (int i = 0; i < 16; i++) {
                float t = float(i) / 15.0 - 0.5;
                sum += sampleClamped(uv + dir * t);
            }
            return sum / 16.0;
        """
        // size, regionScale
        "pixelate" -> """
            vec2 c = abs(uv - 0.5);
            float extent = 0.5 * uParams.y;
            if (c.x > extent || c.y > extent) return texture2D(uInput, uv);
            float block = mix(4.0, 96.0, uParams.x);
            vec2 cell = block / uSize;
            return texture2D(uInput, (floor(uv / cell) + 0.5) * cell);
        """
        // intensity, speed
        "glitch" -> """
            float rate = mix(2.0, 24.0, uParams.y);
            float t = floor(uTime * rate);
            float band = floor(uv.y * mix(6.0, 40.0, hash(vec2(t, 3.0))));
            float active = step(1.0 - uParams.x * 0.6, hash(vec2(band, t)));
            float shift = (hash(vec2(band, t + 7.0)) - 0.5) * 0.15 * uParams.x * active;
            float split = 0.012 * uParams.x * (0.5 + active);
            vec2 p = uv + vec2(shift, 0.0);
            vec4 base = sampleClamped(p);
            float r = sampleClamped(p + vec2(split, 0.0)).r;
            float b = sampleClamped(p - vec2(split, 0.0)).b;
            float line = 1.0 - 0.15 * uParams.x * step(0.5, fract(uv.y * uSize.y * 0.5));
            return vec4(r, base.g, b, base.a) * vec4(vec3(line), 1.0);
        """
        // intensity
        "chromatic" -> """
            vec2 d = (uv - 0.5) * uParams.x * 0.03;
            vec4 base = texture2D(uInput, uv);
            return vec4(sampleClamped(uv + d).r, base.g, sampleClamped(uv - d).b, base.a);
        """
        // style (0 film, 1 VHS, 2 instant photo), size
        "frame.retro" -> """
            vec4 c = texture2D(uInput, uv);
            float border = mix(0.02, 0.1, uParams.y);
            vec2 q = abs(uv - 0.5);
            float style = floor(uParams.x + 0.5);
            if (style < 0.5) {
                vec2 d = max(q - (0.5 - border), 0.0) / border;
                float edge = smoothstep(0.6, 1.0, length(d));
                float vignette = 1.0 - 0.5 * dot(uv - 0.5, uv - 0.5);
                return vec4(mix(c.rgb * vignette, vec3(0.02), edge), c.a);
            } else if (style < 1.5) {
                float scan = 0.85 + 0.15 * sin(uv.y * uSize.y * 1.5);
                float wobble = noise1(uv.y * 40.0 + uTime * 8.0) * 0.003;
                vec3 bleed = vec3(sampleClamped(uv + vec2(wobble + 0.004, 0.0)).r, c.g, sampleClamped(uv - vec2(0.004, 0.0)).b);
                float grain = (hash(uv * uSize + uTime) - 0.5) * 0.08;
                return vec4(bleed * scan + grain, c.a);
            } else {
                float bottom = border * 3.0;
                bool inside = uv.x > border && uv.x < 1.0 - border && (1.0 - uv.y) > border && uv.y > bottom;
                vec3 warm = c.rgb * vec3(1.05, 1.0, 0.92);
                return inside ? vec4(warm, c.a) : vec4(vec3(0.96, 0.95, 0.92), 1.0);
            }
        """
        // intensity, speed
        "shake" -> """
            float t = uTime * mix(4.0, 30.0, uParams.y);
            vec2 offset = vec2(noise1(t), noise1(t + 37.0)) * 0.04 * uParams.x;
            float zoom = 1.0 + 0.09 * uParams.x;
            return sampleClamped((uv - 0.5) / zoom + 0.5 + offset);
        """
        // intensity, speed
        "zoom.dynamic" -> """
            float period = mix(3.0, 0.5, uParams.y);
            float pulse = 0.5 - 0.5 * cos(6.2831853 * uTime / period);
            float zoom = 1.0 + 0.35 * uParams.x * pulse;
            return texture2D(uInput, (uv - 0.5) / zoom + 0.5);
        """
        // intensity, x
        "flare" -> """
            vec4 c = texture2D(uInput, uv);
            vec2 aspect = vec2(uSize.x / uSize.y, 1.0);
            vec2 source = vec2(uParams.y, 0.75);
            vec2 d = (uv - source) * aspect;
            float glow = 0.08 / (dot(d, d) * 40.0 + 0.08);
            float streak = exp(-abs(d.y) * 120.0) * exp(-abs(d.x) * 2.5);
            vec3 light = vec3(1.0, 0.85, 0.6) * (glow + 0.6 * streak);
            vec2 axis = vec2(0.5) - source;
            for (int i = 1; i <= 3; i++) {
                vec2 g = (uv - (source + axis * (0.6 * float(i)))) * aspect;
                light += vec3(0.4, 0.6, 1.0) * 0.15 * smoothstep(0.06, 0.0, length(g) - 0.02 * float(i));
            }
            return vec4(c.rgb + light * uParams.x, c.a);
        """
        // intensity, speed, size
        "particles" -> """
            vec4 c = texture2D(uInput, uv);
            vec2 aspect = vec2(uSize.x / uSize.y, 1.0);
            vec3 add = vec3(0.0);
            for (int layer = 0; layer < 3; layer++) {
                float scale = mix(6.0, 18.0, float(layer) / 2.0);
                vec2 p = uv * aspect * scale;
                p.y -= uTime * mix(0.2, 1.5, uParams.y) * (1.0 + float(layer) * 0.3);
                vec2 cell = floor(p);
                vec2 local = fract(p) - 0.5;
                float h = hash(cell + float(layer) * 13.0);
                vec2 jitter = vec2(hash(cell + 1.7), hash(cell + 9.1)) - 0.5;
                float r = mix(0.03, 0.12, uParams.z) * (0.5 + h);
                float twinkle = 0.6 + 0.4 * sin(uTime * 3.0 + h * 40.0);
                add += step(0.55, h) * smoothstep(r, 0.0, length(local - jitter * 0.6)) * twinkle * vec3(1.0, 0.92, 0.75);
            }
            return vec4(c.rgb + add * uParams.x, c.a);
        """
        // intensity, speed
        "vhs" -> """
            float t = uTime * mix(0.5, 3.0, uParams.y);
            // Wobbling lines, a slow tracking band, color bleed and scanlines.
            float wobble = noise1(uv.y * 40.0 + t * 8.0) * 0.004 * uParams.x;
            float band = smoothstep(0.05, 0.0, abs(fract(uv.y - t * 0.12) - 0.5) - 0.03);
            vec2 q = uv + vec2(wobble + band * 0.02 * uParams.x, 0.0);
            float bleed = 0.006 * uParams.x;
            vec4 c = sampleClamped(q);
            c.r = sampleClamped(q + vec2(bleed, 0.0)).r;
            c.b = sampleClamped(q - vec2(bleed, 0.0)).b;
            float scan = 0.92 + 0.08 * sin(uv.y * uSize.y * 1.6);
            vec3 tinted = mix(c.rgb, c.rgb * vec3(1.05, 0.98, 0.9), uParams.x) * mix(1.0, scan, uParams.x);
            tinted += band * 0.12 * uParams.x * (hash(floor(uv * uSize * 0.5) + t) - 0.3);
            return vec4(tinted, c.a);
        """
        // mode: 0 left half mirrored, 1 top half mirrored, 2 four-way
        "mirror" -> """
            vec2 q = uv;
            if (uParams.x < 0.5) {
                q.x = q.x > 0.5 ? 1.0 - q.x : q.x;
            } else if (uParams.x < 1.5) {
                q.y = q.y < 0.5 ? 1.0 - q.y : q.y;
            } else {
                q = vec2(q.x > 0.5 ? 1.0 - q.x : q.x, q.y < 0.5 ? 1.0 - q.y : q.y);
            }
            return texture2D(uInput, q);
        """
        // segments, speed
        "kaleidoscope" -> """
            vec2 aspect = vec2(uSize.x / uSize.y, 1.0);
            vec2 d = (uv - 0.5) * aspect;
            float segments = floor(mix(3.0, 12.0, uParams.x));
            float slice = 6.2831853 / segments;
            float angle = atan(d.y, d.x) + uTime * mix(0.0, 0.8, uParams.y);
            angle = mod(angle, slice);
            angle = abs(angle - slice * 0.5);
            vec2 p = vec2(cos(angle), sin(angle)) * length(d);
            return sampleClamped(p / aspect + 0.5);
        """
        // intensity, size
        "grain" -> """
            vec4 c = texture2D(uInput, uv);
            vec2 cell = floor(uv * uSize / mix(1.0, 4.0, uParams.y));
            float n = hash(cell + fract(uTime * 24.0) * 97.0) - 0.5;
            // Stronger in mid tones, like film.
            float y = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
            float weight = 1.0 - abs(y - 0.5) * 1.2;
            return vec4(c.rgb + n * 0.35 * uParams.x * weight, c.a);
        """
        // intensity, speed
        "lightleak" -> """
            vec4 c = texture2D(uInput, uv);
            float t = uTime * mix(0.05, 0.6, uParams.y);
            vec2 aspect = vec2(uSize.x / uSize.y, 1.0);
            // Two warm glows drifting slowly from the edges, added like light.
            vec2 a = vec2(0.1 + 0.3 * sin(t), 0.2 + 0.2 * cos(t * 0.7));
            vec2 b = vec2(0.9 - 0.25 * cos(t * 0.8), 0.85 - 0.2 * sin(t * 1.1));
            float ga = exp(-pow(length((uv - a) * aspect) * 2.2, 2.0));
            float gb = exp(-pow(length((uv - b) * aspect) * 2.6, 2.0));
            vec3 light = ga * vec3(1.0, 0.45, 0.15) + gb * vec3(1.0, 0.75, 0.35);
            return vec4(c.rgb + light * 0.9 * uParams.x * c.a, c.a);
        """
        else -> "return texture2D(uInput, uv);"
    }
}
