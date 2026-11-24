package dev.sargunv.pocketgoal

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Finds which game a disc image holds, for a disc image that another app opens with this one, as a
 * frontend does to launch a game. The extractor reads the disc's root folder for the game's
 * executable, whose name is the disc's serial, and looks the serial up in its database of known
 * discs (decompiler/extractor/main.cpp's identify_disc). It takes about a second, even for a CHD.
 */
internal object DiscIdentifier {
  private const val TAG = "OpenGOAL"
  private const val PREFIX = "identified-game: "

  /**
   * [disc]'s game, or null if it isn't a disc of a game the app plays. Call off the main thread.
   */
  fun identify(context: Context, disc: Uri): Game? {
    val file =
      runCatching { context.contentResolver.openFileDescriptor(disc, "r") }
        .onFailure { Log.w(TAG, "Can't open $disc", it) }
        .getOrNull() ?: return null
    // A copy, as Installer.openIso explains
    return file.use { it.dup() }.use { identify(context, it) }
  }

  private fun identify(context: Context, disc: ParcelFileDescriptor): Game? {
    val extractor = File(context.applicationInfo.nativeLibraryDir, "libextractor.so")
    val argv =
      arrayOf(
        extractor.path,
        // The disc, as the descriptor that the child inherits (FileUtil's open_file)
        "/proc/self/fd/3",
        "--identify",
        "--disable-ansi",
      )
    val envp = arrayOf("HOME=${context.cacheDir.path}", "TMPDIR=${context.cacheDir.path}")
    val (pid, output) = NativeProcess.spawn(argv, envp, context.cacheDir.path, disc.fd)
    val lines =
      ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(output))
        .bufferedReader()
        .readLines()
    val status = NativeProcess.waitFor(pid)
    lines.forEach { Log.d(TAG, it) }
    val id = lines.firstNotNullOfOrNull { it.substringAfter(PREFIX, "").ifEmpty { null } }
    Log.i(TAG, "Identified the disc as ${id ?: "no known game"}, with status $status")
    return Game.entries.find { it.id == id?.trim() }.takeIf { status == 0 }
  }
}
