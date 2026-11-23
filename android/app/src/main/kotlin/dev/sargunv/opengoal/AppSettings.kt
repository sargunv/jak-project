package dev.sargunv.opengoal

import android.content.Context

/**
 * The app's own settings. The launcher writes them, and the game's process reads them when it
 * starts, so they're plain SharedPreferences, which a new process reads from disk.
 */
internal object AppSettings {
  private const val FILE = "settings"
  private const val SWAP_FACE_BUTTONS = "swap_face_buttons"

  private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Whether the game swaps A with B and X with Y (OG_SWAP_FACE_BUTTONS in game_controller.cpp). */
  fun swapFaceButtons(context: Context) = prefs(context).getBoolean(SWAP_FACE_BUTTONS, false)

  fun setSwapFaceButtons(context: Context, swap: Boolean) {
    // Written before the game can start, since the game reads it in another process
    prefs(context).edit().putBoolean(SWAP_FACE_BUTTONS, swap).commit()
  }
}
