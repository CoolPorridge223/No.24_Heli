#version 150
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int FogShape;
out float vertexDistance;
out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    // 顶点在缓存锚点附近，使用实际观察坐标计算雾距，不能把机腹局部距离当成相机距离。
    vec4 viewPosition = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * viewPosition;
    vertexDistance = fog_distance(viewPosition.xyz, FogShape);
    vertexColor = Color;
    texCoord0 = UV0;
}
