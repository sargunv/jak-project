package dev.sargunv.opengoal

import android.system.Os
import org.libsdl.app.SDLActivity

/**
 * Runs the game runtime (`gk`) through SDL. SDL is linked statically into libmain.so, and SDL calls
 * the runtime's own `main`, so the runtime needs no Android-specific entry point.
 *
 * For development, launch with intent extras to pick the game and its arguments:
 * ```
 * adb shell am start -n dev.sargunv.opengoal/.GameActivity --es game jak1 --es args "-boot -fakeiso" \
 *   --es env "OG_SCREENSHOT_FRAMES=600,1200"
 * ```
 */
class GameActivity : SDLActivity() {
  override fun getLibraries() = arrayOf("main")

  override fun getMainFunction() = "main"

  override fun getArguments(): Array<String> {
    // The runtime falls back to $HOME for anything not covered by --config-path.
    Os.setenv("HOME", filesDir.path, true)

    // Development: environment variables for the runtime, such as OG_SCREENSHOT_FRAMES=600,1200
    intent.getStringExtra("env")?.split(' ')?.forEach { assignment ->
      val (name, value) = assignment.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
      if (name.isNotEmpty()) Os.setenv(name, value, true)
    }

    // The data folder: the files the APK ships (GameFiles), and the game data that the desktop
    // tools extracted and compiled. External app storage lets `adb push` reach it.
    val dataDir = requireNotNull(getExternalFilesDir(null)).resolve("data")
    GameFiles.install(this, dataDir)
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
