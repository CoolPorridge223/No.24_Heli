#version 150
#moj_import <fog.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D SceneColor;
uniform sampler2D BlockAtlas;
uniform sampler2D FontAtlas;
uniform sampler2D LightDepth;
uniform sampler2D GlassLightmap;
uniform sampler2D GlassSpecular;
uniform vec2 GlassLight;
uniform int GlassPbr;
uniform vec3 GlassSkyColor;
uniform vec3 GlassSkyUp;
uniform mat4 ProjMat;
uniform mat4 LightMatrix;
uniform mat4 InverseProjection;
uniform vec2 ScreenSize;
uniform float DepthScale;
uniform vec2 DepthJitter;
uniform vec3 LightOrigin;
uniform vec3 LightDirection;
uniform float LightConeOffset;
uniform int LightMode;
uniform vec4 LampColor;
uniform float FogStart;
uniform float FogEnd;
in float vertexDistance;
in vec3 localPosition;
in vec3 viewPosition;
in vec2 materialUv;
in vec2 materialOverlayUv;
in vec4 materialTint;
in vec4 materialOverlayTint;
in vec3 surfaceNormal;
out vec4 fragColor;

// 反射只查询已有场景快照，不能写入天空深度；屏幕外或无命中时由调用方使用天空近似。
vec4 glassReflection(vec3 normal, float roughness) {
    vec3 direction = reflect(normalize(viewPosition), normal);
    vec3 origin = viewPosition + normal * 0.025;
    float previousDistance = 0.0;
    // 二十四个递增步长覆盖约四十六格，只在玻璃片元且有 PBR 材质时执行。
    for (int step = 1; step <= 24; step++) {
        float distance = 0.08 * float(step * step);
        vec3 point = origin + direction * distance;
        vec4 clip = ProjMat * vec4(point, 1.0);
        if (clip.w <= 0.0) break;
        vec2 uv = clip.xy / clip.w * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
        float depth = texture(SceneDepth, uv * DepthScale + DepthJitter).r;
        vec4 position = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
        if (depth < 1.0 && point.z < position.z / position.w) {
            float low = previousDistance, high = distance;
            // 二分细化交点后再检查厚度，拒绝跨越轮廓时误命中远处物体。
            for (int refine = 0; refine < 5; refine++) {
                float middle = (low + high) * 0.5;
                point = origin + direction * middle;
                clip = ProjMat * vec4(point, 1.0);
                uv = clip.xy / clip.w * 0.5 + 0.5;
                depth = texture(SceneDepth, uv * DepthScale + DepthJitter).r;
                position = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
                if (depth < 1.0 && point.z < position.z / position.w) high = middle;
                else low = middle;
            }
            float gap = position.z / position.w - point.z;
            if (depth >= 1.0 || gap < -0.05 || gap > 0.15 + high * 0.01) return vec4(0.0);
            vec2 border = min(uv, vec2(1.0) - uv);
            float confidence = smoothstep(0.0, 0.08, min(border.x, border.y));
            // 光滑度降低时扩大颜色采样范围，避免粗糙玻璃仍然呈现锐利镜像。
            vec2 radius = vec2(roughness * 0.025);
            vec3 color = texture(SceneColor, uv).rgb * 0.4;
            color += (texture(SceneColor, clamp(uv + vec2(radius.x, 0), 0.0, 1.0)).rgb
                    + texture(SceneColor, clamp(uv - vec2(radius.x, 0), 0.0, 1.0)).rgb
                    + texture(SceneColor, clamp(uv + vec2(0, radius.y), 0.0, 1.0)).rgb
                    + texture(SceneColor, clamp(uv - vec2(0, radius.y), 0.0, 1.0)).rgb) * 0.15;
            return vec4(color, confidence);
        }
        previousDistance = distance;
    }
    return vec4(0.0);
}

// 以接收面在阴影纹理中的深度梯度补偿采样位置；避免斜墙自阴影，也不靠大偏移漏过薄墙。
float lightVisibility() {
    vec4 clip = LightMatrix * vec4(localPosition, 1.0);
    vec3 projected = clip.xyz / clip.w * 0.5 + 0.5;
    if (clip.w <= 0.0 || any(lessThan(projected, vec3(0.0))) || any(greaterThan(projected, vec3(1.0)))) return 0.0;
    vec3 dx = dFdx(projected), dy = dFdy(projected);
    float det = dx.x * dy.y - dx.y * dy.x;
    vec2 gradient = abs(det) > 1e-12 ? vec2(dy.y * dx.z - dx.y * dy.z, dx.x * dy.z - dy.x * dx.z) / det : vec2(0.0);
    vec2 size = vec2(textureSize(LightDepth, 0));
    vec2 texel = projected.xy * size - 0.5;
    vec2 base = floor(texel), weight = fract(texel);
    float visibility = 0.0;
    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
        vec2 uv = (base + vec2(x, y) + 0.5) / size;
        float receiver = projected.z + dot(gradient, uv - projected.xy);
        float visible = step(receiver - 0.000002, texture(LightDepth, uv).r);
        visibility += visible * (x == 0 ? 1.0 - weight.x : weight.x) * (y == 0 ? 1.0 - weight.y : weight.y);
    }
    return visibility;
}

void main() {
    // 独立灯头深度遍历只读取材质透明度，不能读取正在写入的深度或主相机快照。
    if (LightMode == 4) {
        if (texture(BlockAtlas, materialUv).a * materialTint.a < 0.1) discard;
        fragColor = vec4(0.0);
        return;
    }
    vec2 screen = gl_FragCoord.xy / ScreenSize;
    float depth = texture(SceneDepth, screen * DepthScale + DepthJitter).r;
    vec4 savedPosition = InverseProjection * vec4(screen * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float savedZ = savedPosition.z / savedPosition.w;
    // 背光字形只保留半毫米基础容差，不能漏过紧贴表面的指针而呈现悬浮文字。
    float tolerance = max(LightMode == 2 || LightMode == 3 || LightMode == 7 ? 0.0005 : 0.08, fwidth(viewPosition.z) * 1.5 / DepthScale);
    if (viewPosition.z < savedZ - tolerance) discard;
    // 导航为个人目镜覆盖层，颜色与透明度独立于光影材质和雾；场景深度仍遮挡机体及地形。
    if (LightMode == 6) {
        fragColor = materialTint;
        return;
    }
    float fogVisibility = 1.0 - smoothstep(FogStart, max(FogStart + 0.001, FogEnd), vertexDistance);
    if (LightMode == 7) {
        // 整窗保持 20% 不透明度；反射在此预算内混合，不把掠射角变成实心玻璃。
        vec3 normal = normalize(surfaceNormal);
        float facing = clamp(abs(dot(normal, normalize(-viewPosition))), 0.0, 1.0);
        float fresnel = pow(1.0 - facing, 5.0);
        vec2 edgeDistance = min(materialUv, vec2(1.0) - materialUv);
        float edge = 1.0 - smoothstep(0.0, 0.025, min(edgeDistance.x, edgeDistance.y));
        // 窗缘使用整窗 UV，不在细分面重复；残骸亮度随顶点色压暗，夜晚由真实光照贴图降亮。
        vec3 tint = materialTint.rgb * (0.78 + 0.22 * max(fresnel, edge));
        vec3 light = texture(GlassLightmap, clamp(GlassLight, vec2(0.5 / 16.0), vec2(15.5 / 16.0))).rgb;
        vec3 color = tint * light;
        if (GlassPbr != 0) {
            vec2 specular = texture(GlassSpecular, materialUv).rg;
            float roughness = pow(1.0 - specular.r, 2.0);
            // 玻璃为非金属，只解释 LabPBR 的 0..229 线性 F0 区间。
            float f0 = min(specular.g, 229.0 / 255.0);
            float reflectance = f0 + (1.0 - f0) * fresnel;
            vec3 direction = reflect(normalize(viewPosition), normal);
            float skyLight = clamp((GlassLight.y - 0.03125) / 0.9375, 0.0, 1.0);
            vec3 environment = GlassSkyColor * skyLight
                    * mix(0.25, 1.0, smoothstep(-0.3, 0.6, dot(direction, GlassSkyUp)));
            vec4 reflected = glassReflection(normal, roughness);
            vec3 reflection = mix(environment, reflected.rgb, reflected.a);
            color = mix(color, reflection, clamp(reflectance * (1.0 - roughness) / materialTint.a, 0.0, 1.0));
        }
        fragColor = vec4(color, materialTint.a * fogVisibility);
        return;
    }
    if (LightMode == 2 || LightMode == 3) {
        vec4 glyph = texture(FontAtlas, materialUv);
        if (LightMode == 3) glyph = vec4(1.0, 1.0, 1.0, glyph.r);
        fragColor = glyph * materialTint;
        fragColor.rgb *= fogVisibility;
        return;
    }
    if (LightMode == 1) {
        fragColor = vec4(LampColor.rgb * fogVisibility, 0.0);
        return;
    }
    if (depth >= 1.0 || abs(viewPosition.z - savedZ) > tolerance) discard;
    vec3 relative = localPosition - LightOrigin;
    // 锥尖后移使灯面截面与镜片同大；真实灯面后方不发光，阴影近裁剪也落在该平面。
    if (dot(relative, LightDirection) < 0.0) discard;
    float distanceToLight = length(relative);
    vec3 ray = normalize(relative + LightDirection * LightConeOffset);
    float cone = dot(ray, LightDirection);
    float incidence = max(dot(normalize(surfaceNormal), -ray), 0.0);
    float visibility = lightVisibility();
    float strength = smoothstep(0.9781476, 0.9975641, cone)
        * max(0.0, 1.0 - distanceToLight * distanceToLight / 4096.0) * fogVisibility * sqrt(incidence) * visibility;
    vec3 scene = texture(SceneColor, screen).rgb;
    // 材质颜色独立于太阳阴影；遮挡使用灯头透视深度，不能沿世界竖直方向投影。
    vec4 material = texture(BlockAtlas, materialUv) * materialTint;
    vec4 overlay = texture(BlockAtlas, materialOverlayUv) * materialOverlayTint;
    material.rgb = mix(material.rgb, overlay.rgb, overlay.a);
    if (material.a < 0.1) discard;
    // 探照灯的暖色目标亮度提高到原来的两倍，柔边、距离衰减和遮挡仍共同控制补光。
    vec3 illuminated = material.rgb * vec3(0.95, 0.93, 0.86) * 2.0;
    vec3 addition = max(illuminated - scene, vec3(0.0)) * strength;
    // 实体和方块实体可由多个同面材质层组成，沿用透明度合成，避免重复加法导致过曝。
    fragColor = LightMode == 5 ? vec4(scene + addition, material.a) : vec4(addition, 0.0);
}
