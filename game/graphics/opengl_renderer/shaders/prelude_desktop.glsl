#version 410 core
// Shader.cpp puts this in place of each shader's own #version line on desktop OpenGL.
// prelude_gles.glsl defines the same macros for OpenGL ES.

#define NOPERSPECTIVE noperspective
#define SAMPLER_1D sampler1D
#define TEXEL_FETCH_1D(t, i, lod) texelFetch(t, i, lod)
