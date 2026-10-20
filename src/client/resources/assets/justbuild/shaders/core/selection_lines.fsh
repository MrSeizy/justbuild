#version 330

#moj_import <minecraft:dynamictransforms.glsl>

in vec4 vertexColor;
noperspective in float edgeDistance;
flat in float halfWidth;

out vec4 fragColor;

void main() {
    // Screen-space interpolation keeps the feather about one pixel wide at any distance or angle.
    float feather = max(fwidth(edgeDistance), 1.0);
    float coverage = 1.0 - smoothstep(halfWidth - feather * 0.5, halfWidth + feather * 0.5, abs(edgeDistance));
    fragColor = vertexColor * ColorModulator;
    fragColor.a *= coverage;
}
