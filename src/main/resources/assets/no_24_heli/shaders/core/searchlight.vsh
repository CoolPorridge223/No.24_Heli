#version 150
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
out float vertexDistance;
out vec3 localPosition;
out vec3 viewPosition;
out vec2 materialUv;
out vec2 materialOverlayUv;
out vec4 materialTint;
out vec4 materialOverlayTint;
out vec3 surfaceNormal;

void main() {
    // 顶点在缓存锚点附近，使用实际观察坐标计算雾距，不能把机腹局部距离当成相机距离。
    vec4 transformed = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * transformed;
    vertexDistance = fog_distance(transformed.xyz, FogShape);
    localPosition = Position;
    viewPosition = transformed.xyz;
    materialUv = UV0;
    materialOverlayUv = vec2(UV1) / 32767.0;
    materialTint = Color;
    // 探照灯照明使用原局部法线。
    surfaceNormal = Normal;
    materialOverlayTint = vec4(UV2.y & 255, (UV2.x >> 8) & 255, UV2.x & 255, (UV2.y >> 8) & 255) / 255.0;
}
