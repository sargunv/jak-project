# Shaders

Which renderer uses each shader program, what it draws, and where to look for it
when comparing desktop and OpenGL ES rendering. Programs live in
`game/graphics/opengl_renderer/shaders/` as a `.vert` and `.frag` pair, and
`ShaderLibrary` in `Shader.cpp` compiles all of them at startup for every game.
A program that fails to compile stops the game, even if that game never draws
with it.

## Porting

The shaders are written once for both desktop GLSL 4.10 and GLSL ES 3.20.
`Shader.cpp` replaces each shader's `#version` line with `prelude_desktop.glsl`
or `prelude_gles.glsl`. The preludes define macros for the few things the two
languages spell differently:

| Macro                     | Desktop               | OpenGL ES                                                                     |
| ------------------------- | --------------------- | ----------------------------------------------------------------------------- |
| `SAMPLER_1D`              | `sampler1D`           | `sampler2D` (1D textures are 2D textures one texel high)                      |
| `TEXEL_FETCH_1D(t, i, l)` | `texelFetch(t, i, l)` | `texelFetch(t, ivec2(i, 0), l)`                                               |
| `NOPERSPECTIVE`           | `noperspective`       | `noperspective` with `GL_NV_shader_noperspective_interpolation`, else nothing |

Otherwise the code has to be valid in both languages. GLSL ES has no implicit
conversions, so literals need their types: `2.0` for floats and `0u` for uints.

`mise run shaders:check` compiles every shader both ways with glslang. It also
compiles every shader that differs from upstream to desktop SPIR-V, before and
after, and compares the two. Identical SPIR-V shows that a change can't affect
desktop rendering. Changes that do need a visual comparison.

## Programs

Games: 1, 2 and 3 are the Jak games that create the renderer.

### Background

| Program                    | Renderer                      | Draws                                                               | Games   |
| -------------------------- | ----------------------------- | ------------------------------------------------------------------- | ------- |
| `tfrag3`                   | `TFragment`, `Tie3`           | Level terrain (tfrag) and instanced background objects (tie)        | 1, 2, 3 |
| `tfrag3_no_tex`            | `TFragment`                   | Terrain without textures, a debug mode                              | 1, 2, 3 |
| `tie_wind`                 | `Tie3`                        | Instanced background objects that sway in the wind, such as trees   | 1, 2, 3 |
| `etie`, `etie_base`        | `Tie3`                        | Instanced background objects with environment maps (shiny surfaces) | 1, 2, 3 |
| `shrub`                    | `Shrub`                       | Small foliage: grass, bushes                                        | 1, 2, 3 |
| `hfrag`, `hfrag_montage`   | `Hfrag`                       | Height-field terrain (Jak 3's desert)                               | 3       |
| `sky_blend`                | `SkyBlendGPU`, `SkyBlendCPU`  | Blends the sky and cloud textures                                   | 1       |
| `ocean_common`             | `OceanNear`, `OceanMidAndFar` | The ocean surface                                                   | 1, 2, 3 |
| `ocean_texture`            | `OceanTexture` (PC path)      | The animated ocean texture                                          | 1, 2, 3 |
| `ocean_texture_mipmap`     | `OceanTexture`                | Mipmaps of the ocean texture                                        | 1, 2, 3 |
| `ocean_envmap`, `..._haze` | `OceanEnvmap`                 | Environment map and haze on the ocean                               | 2, 3    |

### Foreground

| Program     | Renderer         | Draws                                                              | Games   |
| ----------- | ---------------- | ------------------------------------------------------------------ | ------- |
| `merc2`     | `Merc2`          | Characters and most animated models: Jak, Daxter, NPCs, enemies    | 1, 2, 3 |
| `emerc`     | `Merc2`          | Models with environment maps                                       | 1, 2, 3 |
| `generic`   | `Generic2`       | Foreground models and effects that go through the generic renderer | 1, 2, 3 |
| `eye`       | `EyeRenderer`    | The animated eye textures of Jak, Daxter and other characters      | 1, 2, 3 |
| `shadow`    | `ShadowRenderer` | Shadows                                                            | 1       |
| `shadow2`   | `Shadow2`        | Shadows                                                            | 2, 3    |
| `depth_cue` | `DepthCue`       | Jak 1's depth cue effect, which blurs and fogs the distance        | 1       |

### Sprites and effects

| Program                           | Renderer                    | Draws                                             | Games   |
| --------------------------------- | --------------------------- | ------------------------------------------------- | ------- |
| `sprite3_3d`                      | `Sprite3`                   | Particles and sprites: eco, sparkles, fire, smoke | 1, 2, 3 |
| `sprite_distort`, `..._instanced` | `Sprite3` (distort)         | Distortion sprites, such as heat haze             | 1, 2, 3 |
| `glow_*` (7 programs)             | `GlowRenderer` in `Sprite3` | Glows around lights, with occlusion probes        | 2, 3    |
| `tex_anim`, `plain_texture`       | `TextureAnimator`           | Animated textures                                 | 2, 3    |
| `slow_time`                       | `SlowTimeEffect`            | Jak 3's slow-motion screen effect                 | 2, 3    |

### HUD, screen and debug

| Program                 | Renderer                               | Draws                                                       | Games   |
| ----------------------- | -------------------------------------- | ----------------------------------------------------------- | ------- |
| `direct_basic`          | `DirectRenderer`                       | Untextured direct draws                                     | 1, 2, 3 |
| `direct_basic_textured` | `DirectRenderer`                       | Textured direct draws: HUD, menus, text, and Jak 1's sky    | 1, 2, 3 |
| `debug_red`             | `DirectRenderer`                       | Debug overlay for direct draws                              | 1, 2, 3 |
| `collision`             | `CollideMeshRenderer`                  | Collision mesh, a debug view                                | 1, 2, 3 |
| `post_processing`       | `OpenGLRenderer`                       | The final pass to the window                                | 1, 2, 3 |
| `solid_color`           | `opengl_utils` helpers                 | Full-screen fills                                           | 1, 2, 3 |
| `simple_texture`        | `opengl_utils` helpers, `BlitDisplays` | Full-screen copies                                          | 1, 2, 3 |
| `splash`                | `GLDisplay::init_splash`               | The splash screen at startup (compiled outside the library) | 1, 2, 3 |

### Compiled but never drawn

No renderer selects these, so they only have to compile: `sky`, `sprite_3d`,
`direct_basic_textured_multi_unit`, and `direct2` (`DirectRenderer2` has no
users).

## Where Jak 1 draws them

From `mise run render:compare` over 13 continue points (two frames each, Jak
standing still after the level loads). Each screenshot comes with the shaders
that drew it, counted at `Shader::activate()`.

| Program                                                                                                                                                        | Seen at                                                                                                               |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `tfrag3`, `shrub`, `merc2`, `emerc`, `etie`, `etie_base`, `generic`, `eye`, `shadow`, `sprite3_3d`, `direct_basic`, `direct_basic_textured`, `post_processing` | Every place                                                                                                           |
| `ocean_common`, `ocean_texture`, `ocean_texture_mipmap`                                                                                                        | Geyser Rock, Sandover, Sentinel Beach, Forbidden Jungle, Misty Island, Rock Village, Lost Precursor City, Boggy Swamp |
| `tie_wind`                                                                                                                                                     | Sandover, Sentinel Beach, Forbidden Jungle, Fire Canyon, Lava Tube                                                    |
| `sprite_distort_instanced`                                                                                                                                     | Misty Island, Rock Village, Lost Precursor City, Boggy Swamp, Snowy Mountain, Spider Cave, Gol and Maia's Citadel     |

Jak 1 programs the sweep didn't see:

- `sky_blend`: Jak 1 blends the sky on the CPU by default (`use_sky_cpu`). The
  "Sky CPU" checkbox in the ImGui render window switches to this shader.
- `depth_cue`, `sprite_distort`, `tfrag3_no_tex`, `collision`, `debug_red`:
  effects and debug views that need a specific place or an ImGui toggle.
- `solid_color`, `simple_texture`: helper draws for some screen effects.

Continue point names, for `--places`: `training-start`, `village1-hut`,
`beach-start`, `jungle-start`, `misty-start`, `firecanyon-start`,
`village2-start`, `sunken-start`, `swamp-start`, `snow-start`, `maincave-start`,
`lavatube-start`, `citadel-start`. The full list is in
`goal_src/jak1/engine/level/level-info.gc`.
