#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec3 Normal;
layout(location = 3) in float LineWidth;

layout(location = 0) out vec4 vertexColor;
layout(location = 1) noperspective out float edgeDistance;
layout(location = 2) flat out float halfWidth;

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
    float side = gl_VertexIndex % 2 == 0 ? 1.0 : -1.0;
    point.xy += perpendicular * (side * radius * 2.0 / ScreenSize) * point.w;
    gl_Position = point;
    edgeDistance = side * radius;
    vertexColor = Color;
}
