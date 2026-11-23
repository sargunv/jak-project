# glad for OpenGL ES 3.2

The OpenGL ES loader for Android. `third-party/glad` stays the desktop loader.

Generated with glad 0.1.36, the generator line that made `third-party/glad`:

```sh
uvx --from 'glad==0.1.36' glad --profile core --api 'gles2=3.2' --generator c --spec gl \
  --extensions 'GL_EXT_texture_filter_anisotropic,GL_EXT_multi_draw_arrays,GL_EXT_polygon_offset_clamp,GL_NV_polygon_mode,GL_EXT_clip_control,GL_EXT_depth_clamp' \
  --out-path third-party/glad-gles
```

After generating, change the two includes to relative paths so they can't
resolve to the desktop loader's headers: `#include <glad/glad.h>` in
`src/glad.c` and `#include <KHR/khrplatform.h>` in `include/glad/glad.h`.
