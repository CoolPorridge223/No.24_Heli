#version 150
#moj_import <fog.glsl>

uniform sampler2D SceneDepth;
uniform sampler2D SceneColor;
uniform sampler2D BlockAtlas;
uniform sampler2D FontAtlas;
uniform mat4 InverseProjection;
uniform vec2 ScreenSize;
uniform float DepthScale;
uniform vec2 DepthJitter;
uniform vec3 LightOrigin;
uniform vec3 LightDirection;
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
out vec4 fragColor;

void main() {
    vec2 screen = gl_FragCoord.xy / ScreenSize;
    float depth = texture(SceneDepth, screen * DepthScale + DepthJitter).r;
    vec4 savedPosition = InverseProjection * vec4(screen * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float savedZ = savedPosition.z / savedPosition.w;
    float tolerance = max(LightMode >= 2 ? 0.005 : 0.08, fwidth(viewPosition.z) * 1.5 / DepthScale);
    if (viewPosition.z < savedZ - tolerance) discard;
    float fogVisibility = 1.0 - smoothstep(FogStart, max(FogStart + 0.001, FogEnd), vertexDistance);
    if (LightMode >= 2) {
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
    float distanceToLight = length(relative);
    float cone = dot(relative / max(distanceToLight, 0.001), LightDirection);
    float strength = smoothstep(0.9781476, 0.9975641, cone)
        * max(0.0, 1.0 - distanceToLight * distanceToLight / 4096.0) * fogVisibility;
    vec3 scene = texture(SceneColor, screen).rgb;
    vec4 material = texture(BlockAtlas, materialUv) * materialTint;
    vec4 overlay = texture(BlockAtlas, materialOverlayUv) * materialOverlayTint;
    material.rgb = mix(material.rgb, overlay.rgb, overlay.a);
    if (material.a < 0.1) discard;
    vec3 illuminated = material.rgb * vec3(0.95, 0.93, 0.86);
    fragColor = vec4(max(illuminated - scene, vec3(0.0)) * strength, 0.0);
}
