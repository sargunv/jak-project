package dev.sargunv.opengoal

import android.content.Context
import android.content.res.AssetManager
import android.system.Os
import android.util.Log
import java.io.File

/**
 * Where the app keeps the runtime's files, and the files from the repository that the APK ships as
 * assets (the stageGameFiles task in build.gradle.kts).
 *
 * The runtime's data folder is laid out like the repository: shaders and fonts from the APK, and
 * `out/<game>/` with each installed game's data, which the extractor makes from the game's ISO.
 */
internal object GameFiles {
  private const val TAG = "OpenGOAL"
  private const val ASSET_ROOT = "game-files"
  private const val RUNTIME_LIST = "game-files.txt"
  private const val BUILD_LIST = "build-files.txt"
  private const val VERSION_FILE = ".game-files-version"

  /** The runtime's data folder, which it gets as `--proj-path`. */
  fun dataDir(context: Context): File = context.filesDir.resolve("data")

  /** One game's data, which the runtime needs to play it. */
  fun gameDir(context: Context, game: Game): File = dataDir(context).resolve("out/${game.id}")

  /** Whether the game's data is there to play. */
  fun isInstalled(context: Context, game: Game): Boolean {
    val dir = gameDir(context, game)
    return dir.resolve("fr3").isDirectory && !dir.resolve("iso").list().isNullOrEmpty()
  }

  /** Unpacks the runtime's files into its data folder, once per install or update of the app. */
  fun installRuntimeFiles(context: Context) {
    val dataDir = dataDir(context)
    val version =
      context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
    val versionFile = dataDir.resolve(VERSION_FILE)
    if (versionFile.isFile && versionFile.readText() == version) return

    val paths = runtimePaths(context.assets)
    Log.i(TAG, "Unpacking ${paths.size} game file folders into $dataDir")
    unpack(context.assets, paths, dataDir)
    // The runtime lists this folder (user profiles for the REPL), and it must exist
    dataDir.resolve("goal_src/user").mkdirs()
    versionFile.writeText(version)
  }

  /** Unpacks everything the extractor reads to install [game] into [dir]. */
  fun unpackBuildFiles(context: Context, game: Game, dir: File) {
    val buildPaths =
      context.assets
        .open(BUILD_LIST)
        .bufferedReader()
        .readLines()
        .filter { it.isNotBlank() }
        .map { it.split(' ', limit = 2) }
        .filter { (forGame, _) -> forGame == "*" || forGame == game.id }
        .map { (_, path) -> path }
    unpack(context.assets, runtimePaths(context.assets) + buildPaths, dir)
  }

  /** Makes [link] a symbolic link to [target], unless it's a real folder. */
  fun link(link: File, target: File) {
    val current = runCatching { Os.readlink(link.path) }.getOrNull()
    if (current == target.path) return
    if (current == null && link.isDirectory) return
    link.delete()
    Os.symlink(target.path, link.path)
  }

  private fun runtimePaths(assets: AssetManager) =
    assets.open(RUNTIME_LIST).bufferedReader().readLines().filter { it.isNotBlank() }

  private fun unpack(assets: AssetManager, paths: List<String>, dir: File) {
    for (path in paths) {
      val dest = dir.resolve(path)
      dest.deleteRecursively()
      copy(assets, "$ASSET_ROOT/$path", dest)
    }
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
