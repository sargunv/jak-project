package dev.sargunv.opengoal

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallLogTest {
  private fun parse(isoBytes: Long, lines: List<String>): Pair<List<InstallProgress>, String?> {
    val reports = mutableListOf<InstallProgress>()
    val parser = InstallLogParser(isoBytes, reports::add)
    lines.forEach(parser::feed)
    return reports to parser.lastError
  }

  @Test
  fun followsEachStep() {
    // Lines as the extractor prints them, cut down to a small ISO with two levels
    val (reports, _) =
      parse(
        0x3000,
        listOf(
          "[39:04:623] [info] Extracting ISO to temporary dir at: /data/install/iso_data/_temp",
          "[39:06:105] [info] Extracting 0COMMON.TXT, size 0x1000 offset 0x17e6800...",
          "[39:06:205] [info] Extracting DRI.DGO, size 0x2000 offset 0x43690800...",
          "[39:07:000] [info] Setting up object file DB...",
          "[39:07:001] [info] -Loading 2 DGOs...",
          "[39:08:000] [info] Processing DRI.DGO...",
          "[39:09:000] [info] Processing CTY.DGO...",
          "[  0%] [goalc   ] goal_src/jak2/engine/math/vector-h.gc",
          "[ 50%] [goalc   ]       ",
          "[100%] [copy    ] iso_data/jak2/VAG/VAGWAD.ENG",
        ),
      )
    assertEquals(
      listOf(
        InstallProgress(InstallStep.Extract, 0f),
        InstallProgress(InstallStep.Extract, 1f / 3),
        InstallProgress(InstallStep.Extract, 1f),
        InstallProgress(InstallStep.Decompile, 0f),
        InstallProgress(InstallStep.Decompile, 0.5f),
        InstallProgress(InstallStep.Decompile, 1f),
        InstallProgress(InstallStep.Compile, 0f),
        InstallProgress(InstallStep.Compile, 0.5f),
        InstallProgress(InstallStep.Compile, 1f),
      ),
      reports,
    )
  }

  @Test
  fun reportsAtMostOncePerPercent() {
    val lines =
      (0 until 1000).map { "[12:00:00] [info] Extracting F$it.STR, size 0x1 offset 0x0..." }
    val (reports, _) = parse(1000, lines)
    assertTrue("${reports.size} reports", reports.size in 90..101)
    assertEquals(1f, reports.last().fraction)
  }

  @Test
  fun keepsTheLastError() {
    val (_, error) =
      parse(
        1,
        listOf(
          "[12:00:00] [error] first",
          "[12:00:01] [info] fine",
          "[12:00:02] [error] Disc image size is too small: 0x1000",
        ),
      )
    assertEquals("Disc image size is too small: 0x1000", error)
  }

  @Test
  fun mapsExitStatuses() {
    assertTrue(installError(4040 % 256, null) is InstallError.NotAnIso)
    assertTrue(installError(4041 % 256, null) is InstallError.NotAnIso)
    assertTrue(installError(4001 % 256, null) is InstallError.UnknownIso)
    assertTrue(installError(4020 % 256, null) is InstallError.UnknownIso)
    assertTrue(installError(137, null) is InstallError.Killed)
    assertEquals(InstallError.Failed(1, "x"), installError(1, "x"))
  }

  @Test
  fun readsTheSizeOfAChd() {
    fun chd(version: Int, logicalBytes: Long, codec: String): ByteArray {
      val header = ByteBuffer.allocate(48)
      header.put("MComprHD".toByteArray()).putInt(124).putInt(version)
      if (version == 5) {
        header.put(16, codec.toByteArray())
        header.putLong(32, logicalBytes)
      } else {
        header.putLong(28, logicalBytes)
      }
      return header.array()
    }
    assertEquals(4_000_000_000, discImageBytes(chd(5, 4_000_000_000, "lzma"), 2_000_000_000))
    assertEquals(2048L * 1000, discImageBytes(chd(5, 2448L * 1000, "cdlz"), 1_000_000))
    assertEquals(3_000_000, discImageBytes(chd(4, 3_000_000, ""), 1_000_000))
    assertEquals(1234, discImageBytes(ByteArray(48), 1234))
    assertEquals(1234, discImageBytes(ByteArray(0), 1234))
  }

  /** The parser follows these lines, which upstream could change. */
  @Test
  fun extractorStillLogsWhatTheParserFollows() {
    val root = File(System.getProperty("repoRoot") ?: error("run through Gradle"))
    val expected =
      mapOf(
        "common/util/read_iso_file.cpp" to "\"Extracting {}, size 0x{:x}",
        "decompiler/decompilation_process.cpp" to "\"Setting up object file DB...\"",
        "decompiler/ObjectFile/ObjectFileDB.cpp" to "\"-Loading {} DGOs...\"",
        "decompiler/level_extractor/extract_level.cpp" to "\"Processing {}...\", dgo_name",
        "goalc/make/MakeSystem.cpp" to "\"[{:3d}%] ",
        "decompiler/extractor/extractor_util.h" to "EXTRACTION_INVALID_ISO_PATH = 4040",
      )
    for ((path, text) in expected) {
      assertTrue("$path no longer has $text", root.resolve(path).readText().contains(text))
    }
  }
}
