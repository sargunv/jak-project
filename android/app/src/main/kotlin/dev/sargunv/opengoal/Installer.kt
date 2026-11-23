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
      GameFiles.unpackBuildFiles(context, game, workDir)
      if (cancelled) return InstallResult.Cancelled

      val status =
        context.contentResolver.openFileDescriptor(iso, "r").use { file ->
          requireNotNull(file) { "no file for $iso" }
          // The ISO's files, the copies the build makes of most of them, and the converted data
          val needed = file.statSize * 5 / 2
          if (workDir.usableSpace < needed) {
            return InstallResult.Failure(InstallError.NotEnoughSpace(needed))
          }
          extract(file, workDir)
        }
      if (cancelled) return InstallResult.Cancelled
      if (status.exitStatus != 0) return InstallResult.Failure(status.toError())

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

  /** Stops a running install. [run] returns [InstallResult.Cancelled]. */
  fun cancel() {
    cancelled = true
    pid.takeIf { it > 0 }?.let { Os.kill(it, OsConstants.SIGKILL) }
  }

  private class ExtractorStatus(val exitStatus: Int, val lastError: String?)

  private fun extract(iso: ParcelFileDescriptor, workDir: File): ExtractorStatus {
    val extractor = File(context.applicationInfo.nativeLibraryDir, "libextractor.so")
    val argv =
      arrayOf(
        extractor.path,
        // The ISO, as the descriptor that the child inherits (FileUtil's open_file)
        "/proc/self/fd/3",
        "--game",
        game.id,
        "--extract",
        "--validate",
        "--decompile",
        "--compile",
        "--proj-path",
        workDir.path,
        "--disable-ansi",
      )
    val envp = arrayOf("HOME=${workDir.path}", "TMPDIR=${context.cacheDir.path}")
    val (childPid, output) = NativeProcess.spawn(argv, envp, workDir.path, iso.fd)
    pid = childPid
    if (cancelled) cancel()

    val parser = ProgressParser(iso.statSize, onProgress)
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

  private fun ExtractorStatus.toError(): InstallError =
    when (exitStatus) {
      // ExtractorErrorCode in decompiler/extractor/extractor_util.h, as exit statuses mod 256
      4040 % 256,
      4041 % 256 -> InstallError.NotAnIso(lastError)
      in 4000 % 256..4020 % 256 -> InstallError.UnknownIso(lastError)
      else -> InstallError.Failed(exitStatus, lastError)
    }

  /** Turns the extractor's log into progress. */
  private class ProgressParser(
    private val isoBytes: Long,
    private val onProgress: (InstallProgress) -> Unit,
  ) {
    var lastError: String? = null
      private set

    private var step = InstallStep.Prepare
    private var extractedBytes = 0L
    private var levels = 0
    private var levelsDone = 0
    private var lastReport: InstallProgress? = null

    fun feed(line: String) {
      val message = line.substringAfter("] ", line).let { it.substringAfter("] ", it) }
      if (line.contains("[error]")) lastError = message.trim()

      when {
        step <= InstallStep.Extract && message.startsWith("Extracting ") -> {
          step = InstallStep.Extract
          EXTRACTING.find(message)?.let { extractedBytes += it.groupValues[1].toLong(16) }
          report(extractedBytes.toFloat() / isoBytes)
        }
        step < InstallStep.Decompile && message.startsWith("Setting up object file DB") -> {
          step = InstallStep.Decompile
          report(0f)
        }
        step == InstallStep.Decompile && levels == 0 && LOADING.matches(message) -> {
          levels = LOADING.find(message)!!.groupValues[1].toInt()
        }
        step == InstallStep.Decompile && PROCESSING.matches(message) -> {
          levelsDone++
          if (levels > 0) report(levelsDone.toFloat() / levels)
        }
        else ->
          BUILD_PERCENT.find(line)?.let {
            step = InstallStep.Compile
            report(it.groupValues[1].toInt() / 100f)
          }
      }
    }

    private fun report(fraction: Float) {
      val progress = InstallProgress(step, fraction.coerceIn(0f, 1f))
      // At most one update per percent, since the log has thousands of lines
      val last = lastReport
      if (
        last == null ||
          last.step != progress.step ||
          progress.fraction!! - last.fraction!! >= 0.01f ||
          progress.fraction == 1f
      ) {
        lastReport = progress
        onProgress(progress)
      }
    }

    companion object {
      val EXTRACTING = Regex("""^Extracting .*, size 0x([0-9a-fA-F]+)""")
      val LOADING = Regex("""^-Loading (\d+) DGOs\.\.\.$""")
      val PROCESSING = Regex("""^Processing \S+\.DGO\.\.\.$""")
      val BUILD_PERCENT = Regex("""^\[\s*(\d+)%]""")
    }
  }

  companion object {
    private const val TAG = "OpenGOAL"

    fun workDir(context: Context): File = context.filesDir.resolve("install")

    /** Moves a folder, or copies it where it can't be moved, as into a link to other storage. */
    private fun move(from: File, to: File) {
      if (!from.renameTo(to)) {
        from.copyRecursively(to, overwrite = true)
        from.deleteRecursively()
      }
    }
  }
}
