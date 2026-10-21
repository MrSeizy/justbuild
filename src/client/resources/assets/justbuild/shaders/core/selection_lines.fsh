#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in vec4 vertexColor;
layout(location = 1) noperspective in float edgeDistance;
layout(location = 2) flat in float halfWidth;

layout(location = 0) out vec4 fragColor;

void main() {
    // Screen-space interpolation keeps the feather about one pixel wide at any distance or angle.
    float feather = max(fwidth(edgeDistance), 1.0);
    float coverage = 1.0 - smoothstep(halfWidth - feather * 0.5, halfWidth + feather * 0.5, abs(edgeDistance));
    fragColor = vertexColor * ColorModulator;
    fragColor.a *= coverage;
}
