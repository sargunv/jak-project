#pragma once

#include <string>
#include <utility>
#include <vector>

#include "common/common_types.h"
#include "common/versions/versions.h"

/*!
 * Put the prelude for the current OpenGL API (prelude_desktop.glsl or prelude_gles.glsl) in place
 * of the shader's own #version line. The preludes define the macros that let one shader source
 * build for both desktop OpenGL and OpenGL ES.
 */
std::string shader_source_for_context(const std::string& source);

class Shader {
 public:
  static constexpr char shader_folder[] = "game/graphics/opengl_renderer/shaders/";
  Shader(const std::string& shader_name, GameVersion version);
  Shader() = default;
  void activate() const;
  bool okay() const { return m_is_okay; }
  u64 id() const { return m_program; }
  const std::string& name() const { return m_name; }

  // How many times activate() ran since the last reset, to see which shaders draw a frame
  u32 activations() const { return m_activations; }
  void reset_activations() { m_activations = 0; }

 private:
  std::string m_name;
  mutable u32 m_activations = 0;
  u64 m_frag_shader = 0;
  u64 m_vert_shader = 0;
  u64 m_program = 0;
  bool m_is_okay = false;
};

// note: update the constructor in Shader.cpp
enum class ShaderId {
  SOLID_COLOR = 0,
  DIRECT_BASIC = 1,
  DIRECT_BASIC_TEXTURED = 2,
  DEBUG_RED = 3,
  SKY = 4,
  SKY_BLEND = 5,
  TFRAG3 = 6,
  TFRAG3_NO_TEX = 7,
  SPRITE = 8,
  SPRITE3 = 9,
  DIRECT2 = 10,
  EYE = 11,
  GENERIC = 12,
  OCEAN_TEXTURE = 13,
  OCEAN_TEXTURE_MIPMAP = 14,
  OCEAN_COMMON = 15,
  SHADOW = 16,
  SHRUB = 17,
  COLLISION = 18,
  MERC2 = 19,
  SPRITE_DISTORT = 20,
  SPRITE_DISTORT_INSTANCED = 21,
  POST_PROCESSING = 22,
  DEPTH_CUE = 23,
  EMERC = 24,
  GLOW_PROBE = 25,
  GLOW_PROBE_READ = 26,
  GLOW_PROBE_READ_DEBUG = 27,
  GLOW_PROBE_DOWNSAMPLE = 28,
  GLOW_DRAW = 29,
  ETIE_BASE = 30,
  ETIE = 31,
  SHADOW2 = 32,
  DIRECT_BASIC_TEXTURED_MULTI_UNIT = 33,
  TEX_ANIM = 34,
  GLOW_DEPTH_COPY = 35,
  GLOW_PROBE_ON_GRID = 36,
  HFRAG = 37,
  HFRAG_MONTAGE = 38,
  PLAIN_TEXTURE = 39,
  TIE_WIND = 40,
  SIMPLE_TEXTURE = 41,
  SLOW_TIME = 42,
  OCEAN_ENVMAP = 43,
  OCEAN_ENVMAP_HAZE = 44,
  MAX_SHADERS
};

class ShaderLibrary {
 public:
  ShaderLibrary(GameVersion version);
  Shader& operator[](ShaderId id) { return m_shaders[(int)id]; }
  Shader& at(ShaderId id) { return m_shaders[(int)id]; }

  /*!
   * The shaders activated since the last call, by name, with how many times each was activated.
   * Renderers that call glUseProgram themselves (the texture animator) aren't counted.
   */
  std::vector<std::pair<std::string, u32>> take_activation_counts();

 private:
  Shader m_shaders[(int)ShaderId::MAX_SHADERS];
};
