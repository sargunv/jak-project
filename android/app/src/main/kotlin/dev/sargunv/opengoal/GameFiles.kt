package dev.sargunv.opengoal

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.File

/**
 * The files from the repository that the runtime reads, such as shaders and fonts. The APK ships
 * them as assets (the stageGameFiles task in build.gradle.kts), and they're unpacked into the data
 * folder once per install or update of the app.
 */
internal object GameFiles {
  private const val TAG = "OpenGOAL"
  private const val ASSET_ROOT = "game-files"
  private const val DIR_LIST = "game-files.txt"
  private const val VERSION_FILE = ".game-files-version"

  fun install(context: Context, dataDir: File) {
    val version =
      context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    val versionFile = dataDir.resolve(VERSION_FILE)
    if (versionFile.isFile && versionFile.readText() == version) return

    val assets = context.assets
    val dirs = assets.open(DIR_LIST).bufferedReader().readLines().filter { it.isNotBlank() }
    Log.i(TAG, "Unpacking ${dirs.size} game file folders into $dataDir")
    for (dir in dirs) {
      val dest = dataDir.resolve(dir)
      dest.deleteRecursively()
      copy(assets, "$ASSET_ROOT/$dir", dest)
    }
    // The runtime lists this folder (user profiles for the REPL), and it must exist
    dataDir.resolve("goal_src/user").mkdirs()
    versionFile.writeText(version)
  }

  private fun copy(assets: AssetManager, path: String, dest: File) {
    val children = assets.list(path).orEmpty()
    if (children.isEmpty()) {
      dest.parentFile?.mkdirs()
      assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
    } else {
      for (child in children) copy(assets, "$path/$child", dest.resolve(child))
    }
  }
}
