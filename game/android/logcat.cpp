/*!
 * @file logcat.cpp
 * Android discards stdout and stderr. The runtime logs through both, so pipe them into logcat.
 */

#include <cstdio>
#include <cstring>
#include <thread>
#include <unistd.h>

#include <android/log.h>

namespace {

constexpr const char* kTag = "OpenGOAL";

void forward_to_logcat(int read_fd) {
  char buffer[4096];
  size_t used = 0;
  while (true) {
    auto count = read(read_fd, buffer + used, sizeof(buffer) - 1 - used);
    if (count <= 0) {
      break;
    }
    used += count;
    // Log each complete line, and flush a full buffer even without a newline.
    size_t start = 0;
    for (size_t i = 0; i < used; i++) {
      if (buffer[i] == '\n') {
        buffer[i] = '\0';
        __android_log_write(ANDROID_LOG_INFO, kTag, buffer + start);
        start = i + 1;
      }
    }
    if (start == 0 && used == sizeof(buffer) - 1) {
      buffer[used] = '\0';
      __android_log_write(ANDROID_LOG_INFO, kTag, buffer);
      used = 0;
    } else if (start > 0) {
      used -= start;
      memmove(buffer, buffer + start, used);
    }
  }
}

__attribute__((constructor)) void redirect_stdio_to_logcat() {
  int fds[2];
  if (pipe(fds) != 0) {
    return;
  }
  setvbuf(stdout, nullptr, _IOLBF, 0);
  setvbuf(stderr, nullptr, _IONBF, 0);
  dup2(fds[1], STDOUT_FILENO);
  dup2(fds[1], STDERR_FILENO);
  close(fds[1]);
  std::thread(forward_to_logcat, fds[0]).detach();
}

}  // namespace
