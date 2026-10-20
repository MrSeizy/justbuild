#version 330

#moj_import <minecraft:globals.glsl>
#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

in vec3 Position;
in vec4 Color;
in vec3 Normal;
in float LineWidth;

out vec4 vertexColor;
noperspective out float edgeDistance;
flat out float halfWidth;

void main() {
    vec4 point = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vec4 direction = ProjMat * ModelViewMat * vec4(Normal, 0.0);
    // Project the tangent without sampling another position that could cross behind the camera.
    vec2 tangent = (direction.xy * point.w - point.xy * direction.w) * ScreenSize;
    float magnitude = length(tangent);
    tangent = magnitude > 0.000001 ? tangent / magnitude : vec2(1.0, 0.0);
    vec2 perpendicular = vec2(-tangent.y, tangent.x);

    halfWidth = LineWidth * 0.5;
    float radius = halfWidth + 1.0; // Extra pixels hold the transparent fringe.
    float side = gl_VertexID % 2 == 0 ? 1.0 : -1.0;
    point.xy += perpendicular * (side * radius * 2.0 / ScreenSize) * point.w;
    gl_Position = point;
    edgeDistance = side * radius;
    vertexColor = Color;
}
