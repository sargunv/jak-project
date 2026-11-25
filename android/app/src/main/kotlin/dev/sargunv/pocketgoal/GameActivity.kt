package dev.sargunv.pocketgoal

import android.content.pm.ApplicationInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.system.Os
import android.util.Log
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.Keep
import kotlin.math.abs
import kotlin.math.roundToInt
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
 * adb shell am start -n dev.sargunv.pocketgoal/.GameActivity --es game jak1 --es args "-boot -fakeiso" \
 *   --es env "OG_SCREENSHOT_FRAMES=600,1200"
 * ```
 */
class GameActivity : SDLActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // The game fills the screen. SDL hides the system bars only once the game's window exists, and
    // a window that started with them showing kept drawing the status bar's background over the top
    // of the game. So the window starts without them.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      window.setDecorFitsSystemWindows(false)
      window.insetsController?.let {
        it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        it.hide(WindowInsets.Type.systemBars())
      }
    }
  }

  override fun getLibraries() = arrayOf("main")

  override fun getMainFunction() = "main"

  override fun getArguments(): Array<String> {
    val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    fun devExtra(name: String) = if (debuggable) intent.getStringExtra(name) else null

    // The runtime falls back to $HOME for anything not covered by --config-path.
    Os.setenv("HOME", filesDir.path, true)

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
    // Saves go where frontends such as Argosy sync a PS2 emulator's: each game's folder on a PS2
    // folder memory card, named like PCSX2's first card, in external storage (kmemcard.cpp)
    val memcardArgs =
      getExternalFilesDir(null)?.let {
        arrayOf("--memcard-path", it.resolve("memcards/Mcd001.ps2").path)
      } ?: emptyArray()

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
      ) +
      memcardArgs +
      "--" +
      gameArgs
  }

  /**
   * Picks the display mode for the game's frame rate: the slowest refresh rate that shows each
   * frame a whole number of times, such as 60 Hz for 60 fps. Otherwise a 120 Hz display shows some
   * frames once and some three times. This overrides a minimum refresh rate in the system's
   * settings, while the game is in front. The runtime calls it when the frame rate changes
   * (activity.cpp).
   */
  @Keep
  fun setGameFrameRate(fps: Float) = runOnUiThread {
    @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
    val current = display.mode
    val mode =
      display.supportedModes
        .filter {
          it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight
        }
        .filter { mode ->
          val frames = mode.refreshRate / fps
          frames >= 0.99f && abs(frames - frames.roundToInt()) < 0.01f
        }
        .minByOrNull { it.refreshRate } ?: return@runOnUiThread
    if (window.attributes.preferredDisplayModeId != mode.modeId) {
      Log.i("OpenGOAL", "Display mode ${mode.refreshRate} Hz for $fps fps")
      window.attributes = window.attributes.apply { preferredDisplayModeId = mode.modeId }
    }
  }

  /**
   * The screen's fastest refresh rate, for the frame rates that the game's menus offer. The current
   * one can be lower, since [setGameFrameRate] changes it. The runtime calls it (activity.cpp).
   */
  @Keep
  fun getMaxRefreshRate(): Float {
    @Suppress("DEPRECATION") val display = windowManager.defaultDisplay
    return display.supportedModes.maxOf { it.refreshRate }
  }

  private val vibrator: Vibrator by lazy {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
      @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
    }
  }

  /**
   * Whether the device has a vibrator, which stands in for a controller's rumble (activity.cpp).
   */
  @Keep fun hasVibrator(): Boolean = vibrator.hasVibrator()

  /**
   * Vibrates the device at [strength], from 0 (stop) to 1, for a controller without a motor of its
   * own, such as the AYN Thor's. As media, since Android treats a vibration without a usage as
   * touch feedback, which the system's settings can turn off.
   */
  @Keep
  fun vibrate(strength: Float, milliseconds: Int) {
    if (strength <= 0f) {
      vibrator.cancel()
      return
    }
    val amplitude =
      if (vibrator.hasAmplitudeControl()) (strength * 255).roundToInt().coerceIn(1, 255)
      else VibrationEffect.DEFAULT_AMPLITUDE
    val effect = VibrationEffect.createOneShot(milliseconds.toLong(), amplitude)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
    } else {
      @Suppress("DEPRECATION")
      vibrator.vibrate(
        effect,
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build(),
      )
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    // The runtime can't start again in this process
    if (isFinishing) Process.killProcess(Process.myPid())
  }
}
