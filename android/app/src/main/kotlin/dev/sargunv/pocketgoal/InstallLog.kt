package dev.sargunv.pocketgoal

/**
 * Turns the extractor's log into progress. It follows log lines from the extractor's source, which
 * InstallLogTest checks still exist.
 */
internal class InstallLogParser(
  private val isoBytes: Long,
  private val onProgress: (InstallProgress) -> Unit,
) {
  /** The last error the extractor logged, without its time and level. */
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

/** The error for an extractor that exited with [exitStatus], and logged [detail] last. */
internal fun installError(exitStatus: Int, detail: String?): InstallError =
  when (exitStatus) {
    // ExtractorErrorCode in decompiler/extractor/extractor_util.h, as exit statuses mod 256
    4040 % 256,
    4041 % 256 -> InstallError.NotAnIso(detail)
    in 4000 % 256..4020 % 256 -> InstallError.UnknownIso(detail)
    // SIGKILL, from Android's low memory killer, since a cancelled install doesn't get here
    128 + 9 -> InstallError.Killed(detail)
    else -> InstallError.Failed(exitStatus, detail)
  }

/**
 * About how many bytes the extractor will copy out of a disc image that starts with [header] and is
 * [fileBytes] long. A CHD is compressed, so its header holds the size: the whole DVD, or for a CD,
 * frames of 2,448 bytes that each hold a 2,048-byte sector.
 */
internal fun discImageBytes(header: ByteArray, fileBytes: Long): Long {
  if (header.size < 48 || String(header, 0, 8, Charsets.US_ASCII) != "MComprHD") return fileBytes
  val buffer = java.nio.ByteBuffer.wrap(header) // big-endian
  val logicalBytes =
    when (buffer.getInt(12)) {
      3,
      4 -> buffer.getLong(28)
      5 -> buffer.getLong(32)
      else -> return fileBytes
    }
  // A version 5 CHD names its codecs, and CD codecs start with "cd"
  val isCd = buffer.getInt(12) == 5 && String(header, 16, 2, Charsets.US_ASCII) == "cd"
  return if (isCd) logicalBytes / 2448 * 2048 else logicalBytes
}
