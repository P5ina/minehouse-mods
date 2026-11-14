#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

// The look of the Rift mode over the finished picture of the world: the edges of the screen sink
// into the colour of the rift, colours split a little there, and when things go badly the picture
// loses colour and the edge beats.

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform RiftConfig {
    // rgb: the colour of the edge; a: how much the shadows lean to it (0..1)
    vec4 EdgeColor;
    // how far the dark edge comes in (0: none, 1: strong)
    float Vignette;
    // 0: full colour, 1: grey
    float Desaturate;
    // how much the edge beats (0: still), about once a second
    float Pulse;
    // how far red and blue part at the edge, in screen widths
    float Aberration;
};

layout(location = 0) out vec4 fragColor;

const vec3 Gray = vec3(0.3, 0.59, 0.11);

void main() {
    vec2 fromCentre = texCoord - 0.5;
    // 0 in the middle, about 1 in the corners, whatever the shape of the window
    float r = length(fromCentre * vec2(1.0, 0.85)) * 1.7;
    float edge = smoothstep(0.38, 1.02, r);

    vec2 shift = fromCentre * edge * Aberration;
    vec3 color = vec3(
        texture(InSampler, texCoord + shift).r,
        texture(InSampler, texCoord).g,
        texture(InSampler, texCoord - shift).b
    );

    float luma = dot(color, Gray);
    color = mix(color, vec3(luma), Desaturate);
    // Shadows take a breath of the rift's colour, lights stay as they are.
    color += EdgeColor.rgb * (1.0 - smoothstep(0.0, 0.6, luma)) * 0.10 * EdgeColor.a;

    // GameTime runs 0..1 over a Minecraft day (20 minutes).
    float seconds = GameTime * 1200.0;
    float beat = 0.5 + 0.5 * sin(seconds * 6.2831853 * 1.1);
    float dark = clamp(edge * Vignette * (1.0 + Pulse * (beat - 0.35)), 0.0, 1.0);
    color = mix(color, EdgeColor.rgb * (0.22 + 0.3 * Pulse * beat), dark);

    fragColor = vec4(color, 1.0);
}
