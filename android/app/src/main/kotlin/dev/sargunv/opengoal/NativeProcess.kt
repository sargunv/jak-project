package dev.sargunv.opengoal

/** Child processes that can read a file the app has open (game/android/process_jni.cpp). */
internal object NativeProcess {
  init {
    System.loadLibrary("og_process")
  }

  /**
   * Runs `argv[0]` in [cwd], with [fileFd], unless it's -1, open as the child's file descriptor 3.
   * Returns the child's pid and the read end of a pipe that carries its stdout and stderr, which
   * the caller owns.
   */
  @JvmStatic
  external fun spawn(argv: Array<String>, envp: Array<String>, cwd: String, fileFd: Int): IntArray

  /** Waits for the child to exit, and returns its exit status, or 128 plus a fatal signal. */
  @JvmStatic external fun waitFor(pid: Int): Int
}
