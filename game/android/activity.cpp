/*!
 * @file activity.cpp
 * Calls into GameActivity, for what only Java can do: the display mode and the vibrator.
 */

#include "activity.h"

#include <jni.h>

#include "third-party/SDL/include/SDL3/SDL_system.h"

namespace {
// Calls a method of GameActivity, the activity that SDL runs in, with the signature given.
template <typename Call>
void with_activity(const char* name, const char* signature, Call call) {
  auto* env = static_cast<JNIEnv*>(SDL_GetAndroidJNIEnv());
  auto activity = static_cast<jobject>(SDL_GetAndroidActivity());
  if (!env || !activity) {
    return;
  }
  jclass activity_class = env->GetObjectClass(activity);
  if (jmethodID method = env->GetMethodID(activity_class, name, signature)) {
    call(env, activity, method);
  }
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
  }
  env->DeleteLocalRef(activity_class);
  env->DeleteLocalRef(activity);
}
}  // namespace

void android_set_frame_rate(float fps) {
  static float last_fps = 0;
  if (fps == last_fps) {
    return;
  }
  last_fps = fps;
  with_activity("setGameFrameRate", "(F)V", [fps](JNIEnv* env, jobject activity, jmethodID method) {
    env->CallVoidMethod(activity, method, fps);
  });
}

int android_max_refresh_rate() {
  static const int rate = [] {
    float result = 0;
    with_activity("getMaxRefreshRate", "()F",
                  [&result](JNIEnv* env, jobject activity, jmethodID method) {
                    result = env->CallFloatMethod(activity, method);
                  });
    return static_cast<int>(result + 0.5f);
  }();
  return rate;
}

bool android_has_vibrator() {
  static const bool has_vibrator = [] {
    bool result = false;
    with_activity("hasVibrator", "()Z", [&result](JNIEnv* env, jobject activity, jmethodID method) {
      result = env->CallBooleanMethod(activity, method);
    });
    return result;
  }();
  return has_vibrator;
}

void android_vibrate(float strength, int milliseconds) {
  with_activity("vibrate", "(FI)V",
                [strength, milliseconds](JNIEnv* env, jobject activity, jmethodID method) {
                  env->CallVoidMethod(activity, method, strength, milliseconds);
                });
}
