package dev.sargunv.opengoal

import android.system.Os
import org.libsdl.app.SDLActivity

/**
 * Runs the game runtime (`gk`) through SDL. SDL is linked statically into libmain.so, and SDL calls
 * the runtime's own `main`, so the runtime needs no Android-specific entry point.
 *
 * For development, launch with intent extras to pick the game and its arguments:
 * ```
 * adb shell am start -n dev.sargunv.opengoal/.GameActivity --es game jak1 --es args "-boot -fakeiso"
 * ```
 */
class GameActivity : SDLActivity() {
  override fun getLibraries() = arrayOf("main")

  override fun getMainFunction() = "main"

  override fun getArguments(): Array<String> {
    // The runtime falls back to $HOME for anything not covered by --config-path.
    Os.setenv("HOME", filesDir.path, true)

    // Game data that the desktop tools extracted and compiled. External app storage lets
    // `adb push` reach it during development.
    val dataDir = requireNotNull(getExternalFilesDir(null)).resolve("data")
    val configDir = filesDir.resolve("config")

    val game = intent.getStringExtra("game") ?: "jak1"
    val gameArgs =
      (intent.getStringExtra("args") ?: "-boot -fakeiso").split(' ').filter {
        it.isNotEmpty()
      }

    return arrayOf(
      "-v",
      // logcat shows ANSI escapes as text
      "--disable-ansi",
      "--game",
      game,
      "--proj-path",
      dataDir.path,
      "--config-path",
      configDir.path,
      "--",
    ) + gameArgs
  }
}
