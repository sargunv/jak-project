/*!
 * @file process_jni.cpp
 * Starts a child process for the app, for NativeProcess.kt. The app runs the extractor this way, as
 * an executable in its native library folder (libextractor.so). Java's ProcessBuilder can't pass
 * the child an open file, and the extractor needs the ISO that the user picked, which the app can
 * only read through the descriptor that the Storage Access Framework gave it.
 */

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <jni.h>
#include <string>
#include <unistd.h>
#include <vector>

#include <sys/wait.h>

namespace {

std::vector<std::string> to_strings(JNIEnv* env, jobjectArray array) {
  std::vector<std::string> result;
  const jsize count = env->GetArrayLength(array);
  for (jsize i = 0; i < count; i++) {
    auto string = static_cast<jstring>(env->GetObjectArrayElement(array, i));
    const char* chars = env->GetStringUTFChars(string, nullptr);
    result.emplace_back(chars);
    env->ReleaseStringUTFChars(string, chars);
    env->DeleteLocalRef(string);
  }
  return result;
}

std::vector<char*> to_pointers(std::vector<std::string>& strings) {
  std::vector<char*> result;
  for (auto& string : strings) {
    result.push_back(string.data());
  }
  result.push_back(nullptr);
  return result;
}

void throw_errno(JNIEnv* env, const char* what) {
  const std::string message = std::string(what) + ": " + strerror(errno);
  env->ThrowNew(env->FindClass("java/io/IOException"), message.c_str());
}

}  // namespace

/*!
 * Runs argv[0] with argv and envp in the folder cwd. The child's file descriptor 3 is a copy of
 * fileFd, unless that's -1, and its stdout and stderr both go to one pipe. Returns the child's pid
 * and the pipe's read end, which the caller owns.
 */
extern "C" JNIEXPORT jintArray JNICALL
Java_dev_sargunv_pocketgoal_NativeProcess_spawn(JNIEnv* env,
                                                jclass,
                                                jobjectArray argv_array,
                                                jobjectArray envp_array,
                                                jstring cwd_string,
                                                jint file_fd) {
  auto args = to_strings(env, argv_array);
  auto envs = to_strings(env, envp_array);
  const char* cwd_chars = env->GetStringUTFChars(cwd_string, nullptr);
  const std::string cwd = cwd_chars;
  env->ReleaseStringUTFChars(cwd_string, cwd_chars);
  // Everything the child needs is ready before fork, which leaves it only async-signal-safe calls
  auto argv = to_pointers(args);
  auto envp = to_pointers(envs);

  int output[2];
  if (pipe2(output, O_CLOEXEC) != 0) {
    throw_errno(env, "pipe2");
    return nullptr;
  }

  const pid_t pid = fork();
  if (pid < 0) {
    close(output[0]);
    close(output[1]);
    throw_errno(env, "fork");
    return nullptr;
  }
  if (pid == 0) {
    // dup2 clears close-on-exec on the copy, unless the copy is the same descriptor
    if (file_fd == 3) {
      fcntl(3, F_SETFD, 0);
    } else if (file_fd >= 0 && dup2(file_fd, 3) < 0) {
      _exit(126);
    }
    if (dup2(output[1], STDOUT_FILENO) < 0 || dup2(output[1], STDERR_FILENO) < 0 ||
        chdir(cwd.c_str()) != 0) {
      _exit(126);
    }
    const int null_fd = open("/dev/null", O_RDONLY);
    if (null_fd >= 0) {
      dup2(null_fd, STDIN_FILENO);
    }
    execve(argv[0], argv.data(), envp.data());
    _exit(127);
  }

  close(output[1]);
  const jint result[] = {pid, output[0]};
  jintArray array = env->NewIntArray(2);
  env->SetIntArrayRegion(array, 0, 2, result);
  return array;
}

/*!
 * Waits for the child to exit. Returns its exit status, or 128 plus the signal that killed it, as
 * shells do.
 */
extern "C" JNIEXPORT jint JNICALL Java_dev_sargunv_pocketgoal_NativeProcess_waitFor(JNIEnv* env,
                                                                                    jclass,
                                                                                    jint pid) {
  int status = 0;
  while (waitpid(pid, &status, 0) < 0) {
    if (errno != EINTR) {
      throw_errno(env, "waitpid");
      return -1;
    }
  }
  if (WIFEXITED(status)) {
    return WEXITSTATUS(status);
  }
  return WIFSIGNALED(status) ? 128 + WTERMSIG(status) : -1;
}
