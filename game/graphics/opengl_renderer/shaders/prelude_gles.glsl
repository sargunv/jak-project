#version 320 es
// Shader.cpp puts this in place of each shader's own #version line on OpenGL ES.
// prelude_desktop.glsl defines the same macros for desktop OpenGL.

// GLSL ES has noperspective only through an extension. Without it, interpolate with perspective.
#ifdef GL_NV_shader_noperspective_interpolation
#extension GL_NV_shader_noperspective_interpolation : enable
#define NOPERSPECTIVE noperspective
#else
#define NOPERSPECTIVE
#endif

precision highp float;
precision highp int;
precision highp sampler2D;
precision highp usampler2D;
precision highp isampler2D;
precision highp sampler2DArray;

// OpenGL ES has no 1D textures. gles_compat.h makes them 2D textures one texel high.
#define SAMPLER_1D sampler2D
#define TEXEL_FETCH_1D(t, i, lod) texelFetch(t, ivec2(i, 0), lod)
