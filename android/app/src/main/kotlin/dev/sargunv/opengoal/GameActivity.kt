package dev.sargunv.opengoal

import android.content.pm.ApplicationInfo
import android.os.Process
import android.system.Os
import org.libsdl.app.SDLActivity

/**
 * Runs the game runtime (`gk`) through SDL. SDL is linked statically into libmain.so, and SDL calls
 * the runtime's own `main`, so the runtime needs no Android-specific entry point.
 *
 * The runtime can only start once per process, so this activity runs in its own process (the
 * manifest), which ends with the game.
 *
 * The launcher starts it with the game to play. Debug builds also take the game's arguments, the
 * runtime's options and environment variables, for development:
 * ```
 * adb shell am start -n dev.sargunv.opengoal/.GameActivity --es game jak1 --es args "-boot -fakeiso" \
 *   --es env "OG_SCREENSHOT_FRAMES=600,1200"
 * ```
 */
class GameActivity : SDLActivity() {
  override fun getLibraries() = arrayOf("main")

  override fun getMainFunction() = "main"

  override fun getArguments(): Array<String> {
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    fun devExtra(name: String) = if (debuggable) intent.getStringExtra(name) else null

    // The runtime falls back to $HOME for anything not covered by --config-path.
    Os.setenv("HOME", filesDir.path, true)

    // The launcher's settings
    if (AppSettings.swapFaceButtons(this)) Os.setenv("OG_SWAP_FACE_BUTTONS", "1", true)

    // Environment variables for the runtime, such as OG_SCREENSHOT_FRAMES=600,1200
    devExtra("env")?.split(' ')?.forEach { assignment ->
      val (name, value) = assignment.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
      if (name.isNotEmpty()) Os.setenv(name, value, true)
    }

    // The runtime's data folder, in internal storage, holds the files the APK ships (GameFiles),
    // and out/ with the games that the launcher installed. In debug builds, push-data.sh can copy
    // a desktop's game data into out/ with run-as. Innkeeper has no run-as, so there out/ can link
    // to external storage, where adb can write.
    val dataDir = GameFiles.dataDir(this)
    GameFiles.installRuntimeFiles(this)
    if (debuggable) {
      val gameData = requireNotNull(getExternalFilesDir(null)).resolve("data/out")
      if (gameData.list()?.isNotEmpty() == true) GameFiles.link(dataDir.resolve("out"), gameData)
    }
    val configDir = filesDir.resolve("config")

    val game = intent.getStringExtra("game") ?: "jak1"
    val gameArgs = (devExtra("args") ?: "-boot -fakeiso").split(' ').filter { it.isNotEmpty() }
    // Runtime options, such as --no-display
    val runtimeArgs = (devExtra("runtime_args") ?: "").split(' ').filter { it.isNotEmpty() }

    return runtimeArgs.toTypedArray() +
      arrayOf(
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
      ) +
      gameArgs
  }

  override fun onDestroy() {
    super.onDestroy()
    // The runtime can't start again in this process
    if (isFinishing) Process.killProcess(Process.myPid())
  }
}
