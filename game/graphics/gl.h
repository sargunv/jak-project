#pragma once

/*!
 * @file gl.h
 * The OpenGL API for the renderer: desktop OpenGL 4.x, or OpenGL ES 3.2 when OG_GLES is defined.
 */

#ifdef OG_GLES
#include "game/graphics/gles_compat.h"
#else
#include "third-party/glad/include/glad/glad.h"
#endif
