package dev.sargunv.opengoal

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/** The steps of installing a game, in order. */
enum class InstallStep {
  /** Unpacking the extractor's files from the APK. */
  Prepare,
  /** Copying the game's files out of the ISO, and checking them against known copies. */
  Extract,
  /** Converting the game's levels, art and text. */
  Decompile,
  /** Compiling the game's code. */
  Compile,
  /** Moving the game's data into place. */
  Finish,
}

/** How far an install has got. [fraction] is null when the step's progress is unknown. */
data class InstallProgress(val step: InstallStep, val fraction: Float?)

/** Why an install failed. */
sealed interface InstallError {
  /** The extractor's last error message, if it printed one. */
  val detail: String?

  data class NotEnoughSpace(val neededBytes: Long, override val detail: String? = null) :
    InstallError

  /** The file isn't an ISO, or not a whole one. */
  data class NotAnIso(override val detail: String?) : InstallError

  /** The ISO isn't a copy of the game that the extractor knows. */
  data class UnknownIso(override val detail: String?) : InstallError

  /** The system stopped the extractor, most likely for memory. */
  data class Killed(override val detail: String?) : InstallError

  data class Failed(val exitStatus: Int, override val detail: String?) : InstallError
}

sealed interface InstallResult {
  data object Success : InstallResult

  data object Cancelled : InstallResult

  data class Failure(val error: InstallError) : InstallResult
}

/**
 * Installs a game from its ISO with the extractor, which the APK ships as `libextractor.so`. The
 * extractor copies the files out of the ISO, decompiles the game's data and compiles its code, in a
 * work folder with the files it needs from the APK. Only the result that the runtime reads moves
 * into the runtime's data folder, and the work folder is deleted.
 *
 * The extractor runs as a child process, so its memory is freed when it exits, and a crash doesn't
 * take the app with it. It reads the ISO through a file descriptor that it inherits, since the app
 * gets the ISO from the Storage Access Framework and the child can't open it by path.
 */
internal class Installer(
  private val context: Context,
  private val game: Game,
  private val iso: Uri,
  private val onProgress: (InstallProgress) -> Unit,
) {
  @Volatile private var pid = 0
  @Volatile private var cancelled = false

  /** Blocks until the install finishes. Call from a background thread. */
  fun run(): InstallResult {
    val workDir = workDir(context)
    try {
      onProgress(InstallProgress(InstallStep.Prepare, null))
      workDir.deleteRecursively()
      workDir.mkdirs()
      workDir.resolve(GAME_FILE).writeText(game.id)
      GameFiles.unpackBuildFiles(context, game, workDir)
      if (cancelled) return InstallResult.Cancelled

      val status =
        openIso().use { file ->
          val header = ByteArray(48)
          val headerBytes = runCatching { Os.pread(file.fileDescriptor, header, 0, header.size, 0) }
          val isoBytes = discImageBytes(header.copyOf(headerBytes.getOrDefault(0)), file.statSize)
          // The ISO's files, the copies the build makes of most of them, and the converted data
          val needed = isoBytes * 5 / 2
          if (workDir.usableSpace < needed) {
            return InstallResult.Failure(InstallError.NotEnoughSpace(needed))
          }
          extract(file, isoBytes, workDir)
        }
      if (cancelled) return InstallResult.Cancelled
      if (status.exitStatus != 0) {
        return InstallResult.Failure(installError(status.exitStatus, status.lastError))
      }

      onProgress(InstallProgress(InstallStep.Finish, null))
      val built = workDir.resolve("out/${game.id}")
      val dest = GameFiles.gameDir(context, game)
      dest.deleteRecursively()
      dest.mkdirs()
      // What the runtime reads: the files it loads as the game's disc, and the converted levels
      for (name in listOf("iso", "fr3")) move(built.resolve(name), dest.resolve(name))
      return InstallResult.Success
    } finally {
      workDir.deleteRecursively()
    }
  }

  /**
   * Opens the ISO as a plain copy of the descriptor the provider gave. While the provider's own
   * descriptor is open, Android ties the app to the provider's process, and kills the app if that
   * process dies, which it did under the install's memory pressure.
   */
  private fun openIso(): ParcelFileDescriptor {
    val file =
      requireNotNull(context.contentResolver.openFileDescriptor(iso, "r")) {
        "no file for $iso"
      }
    return file.use { it.dup() }
  }

  /** Stops a running install. [run] returns [InstallResult.Cancelled]. */
  fun cancel() {
    cancelled = true
    pid.takeIf { it > 0 }?.let { Os.kill(it, OsConstants.SIGKILL) }
  }

  private class ExtractorStatus(val exitStatus: Int, val lastError: String?)

  /**
   * Runs the extractor twice: to extract and decompile, then to compile. The compiler would
   * otherwise start on top of the memory the decompiler grew to, which took Jak 3's install from 5
   * to 5.8 GB.
   */
  private fun extract(iso: ParcelFileDescriptor, isoBytes: Long, workDir: File): ExtractorStatus {
    val parser = InstallLogParser(isoBytes, onProgress)
    val decompiled =
      runExtractor(
        workDir,
        parser,
        iso.fd,
        // The ISO, as the descriptor that the child inherits (FileUtil's open_file)
        "/proc/self/fd/3",
        "--extract",
        "--validate",
        "--decompile",
        // A thread per level took Jak II to 7 GB, and Android stopped the install even with 16 GB.
        // Four peak at 5.4 GB.
        "--decomp-config-override",
        """{"level_extract_threads": 4}""",
      )
    if (decompiled.exitStatus != 0 || cancelled) return decompiled
    return runExtractor(
      workDir,
      parser,
      -1,
      workDir.resolve("iso_data/${game.id}").path,
      "--folder",
      "--compile",
    )
  }

  private fun runExtractor(
    workDir: File,
    parser: InstallLogParser,
    fileFd: Int,
    vararg args: String,
  ): ExtractorStatus {
    val extractor = File(context.applicationInfo.nativeLibraryDir, "libextractor.so")
    val argv =
      arrayOf(extractor.path) +
        args +
        arrayOf("--game", game.id, "--proj-path", workDir.path, "--disable-ansi")
    val envp = arrayOf("HOME=${workDir.path}", "TMPDIR=${context.cacheDir.path}")
    val (childPid, output) = NativeProcess.spawn(argv, envp, workDir.path, fileFd)
    pid = childPid
    if (cancelled) cancel()

    ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.adoptFd(output))
      .bufferedReader()
      .useLines { lines ->
        lines.forEach { line ->
          Log.d(TAG, line)
          parser.feed(line)
        }
      }
    val status = NativeProcess.waitFor(childPid)
    pid = 0
    Log.i(TAG, "Extractor exited with status $status")
    return ExtractorStatus(status, parser.lastError)
  }

  companion object {
    private const val TAG = "OpenGOAL"

    /** Names the game being installed, in the work folder, until the install ends. */
    private const val GAME_FILE = ".game"

    fun workDir(context: Context): File = context.filesDir.resolve("install")

    /**
     * Deletes the work folder of an install that didn't finish, as when the system stopped the app
     * for memory, and returns its game. Call when no install is running.
     */
    fun cleanUpInterrupted(context: Context): Game? {
      val workDir = workDir(context)
      val id = runCatching { workDir.resolve(GAME_FILE).readText() }.getOrNull()
      workDir.deleteRecursively()
      return Game.entries.find { it.id == id }
    }

    /** Moves a folder, or copies it where it can't be moved, as into a link to other storage. */
    private fun move(from: File, to: File) {
      if (!from.renameTo(to)) {
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
      }
    }
  }
}
