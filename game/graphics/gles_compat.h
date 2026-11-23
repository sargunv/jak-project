#pragma once

/*!
 * @file gles_compat.h
 * Stand-ins for the desktop OpenGL features that the renderer uses and OpenGL ES 3.2 lacks.
 * Include through game/graphics/gl.h.
 */

#ifndef OG_GLES
#error "gles_compat.h is only for OpenGL ES builds"
#endif

#include <cstdint>
#include <cstdio>
#include <cstring>

#include "third-party/glad-gles/include/glad/glad.h"

// OpenGL ES has no 1D textures. They become 2D textures one texel high, and shaders sample them
// with sampler2D.
#define GL_TEXTURE_1D GL_TEXTURE_2D

inline void og_glTexImage1D(GLenum target,
                            GLint level,
                            GLint internal_format,
                            GLsizei width,
                            GLint border,
                            GLenum format,
                            GLenum type,
                            const void* pixels) {
  glTexImage2D(target, level, internal_format, width, 1, border, format, type, pixels);
}
#define glTexImage1D og_glTexImage1D

inline void og_glTexSubImage1D(GLenum target,
                               GLint level,
                               GLint xoffset,
                               GLsizei width,
                               GLenum format,
                               GLenum type,
                               const void* pixels) {
  glTexSubImage2D(target, level, xoffset, 0, width, 1, format, type, pixels);
}
#define glTexSubImage1D og_glTexSubImage1D

// With GL_RGBA, the reversed packed format is the byte order of GL_UNSIGNED_BYTE on little-endian
// CPUs, which every Android ABI is. The renderer only uses the unreversed format to allocate
// textures without data, where the byte order doesn't matter.
#define GL_UNSIGNED_INT_8_8_8_8_REV GL_UNSIGNED_BYTE
#define GL_UNSIGNED_INT_8_8_8_8 GL_UNSIGNED_BYTE

// Anisotropic filtering is an extension in OpenGL ES, with the same enum values.
#define GL_TEXTURE_MAX_ANISOTROPY GL_TEXTURE_MAX_ANISOTROPY_EXT
#define GL_MAX_TEXTURE_MAX_ANISOTROPY GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT

// Desktop-only framebuffer status codes, which OpenGL ES never returns. The renderer names them
// when it reports framebuffer errors.
#define GL_FRAMEBUFFER_INCOMPLETE_DRAW_BUFFER 0x8CDB
#define GL_FRAMEBUFFER_INCOMPLETE_READ_BUFFER 0x8CDC

// OpenGL ES only restarts primitives at the largest index of the index type. The renderer always
// restarts at UINT32_MAX, and every indexed draw uses 32-bit indices, which is the same thing.
#define GL_PRIMITIVE_RESTART GL_PRIMITIVE_RESTART_FIXED_INDEX
inline void og_glPrimitiveRestartIndex(GLuint index) {
  if (index != UINT32_MAX) {
    fprintf(stderr, "glPrimitiveRestartIndex(%u): OpenGL ES can only restart at UINT32_MAX\n",
            index);
  }
}
#define glPrimitiveRestartIndex og_glPrimitiveRestartIndex

#define glClearDepth glClearDepthf

// Wireframe drawing is a debug feature. Use the polygon mode extension where the driver has it.
#ifndef GL_LINE
#define GL_LINE 0x1B01
#endif
#ifndef GL_FILL
#define GL_FILL 0x1B02
#endif
inline void og_glPolygonMode(GLenum face, GLenum mode) {
  if (GLAD_GL_NV_polygon_mode) {
    glPolygonModeNV(face, mode);
  }
}
#define glPolygonMode og_glPolygonMode

inline void og_glMultiDrawElements(GLenum mode,
                                   const GLsizei* count,
                                   GLenum type,
                                   const void* const* indices,
                                   GLsizei drawcount) {
  if (GLAD_GL_EXT_multi_draw_arrays) {
    glMultiDrawElementsEXT(mode, count, type, indices, drawcount);
    return;
  }
  for (GLsizei i = 0; i < drawcount; i++) {
    glDrawElements(mode, count[i], type, indices[i]);
  }
}
#define glMultiDrawElements og_glMultiDrawElements

inline void og_glTexImage2DMultisample(GLenum target,
                                       GLsizei samples,
                                       GLenum internal_format,
                                       GLsizei width,
                                       GLsizei height,
                                       GLboolean fixed_sample_locations) {
  glTexStorage2DMultisample(target, samples, internal_format, width, height,
                            fixed_sample_locations);
}
#define glTexImage2DMultisample og_glTexImage2DMultisample

// Only debug texture dumps read textures back. TODO: read through a framebuffer with glReadPixels.
inline void og_glGetTexImage(GLenum target, GLint level, GLenum format, GLenum type, void* pixels) {
  (void)target;
  (void)level;
  (void)format;
  (void)type;
  (void)pixels;
}
#define glGetTexImage og_glGetTexImage
