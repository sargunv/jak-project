import javax.inject.Inject

plugins { alias(libs.plugins.android.application) }

// The repository root holds the CMake project that builds the game runtime (libmain.so).
val repoRoot = rootDir.parentFile

/**
 * Stages the files from this repository that the runtime reads at run time, in the layout of its
 * data folder. The app ships them as assets under `game-files/` and unpacks them (GameFiles.kt), so
 * only the game's extracted data has to be installed separately.
 */
abstract class StageGameFiles : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  @get:Internal abstract val repoRoot: DirectoryProperty

  @get:OutputDirectory abstract val outputDir: DirectoryProperty

  @get:Inject abstract val fileSystem: FileSystemOperations

  @TaskAction
  fun stage() {
    val root = repoRoot.get()
    fileSystem.sync {
      into(outputDir.dir("game-files"))
      for (path in GAME_FILE_DIRS) {
        from(root.dir(path)) { into(path) }
      }
    }
    // The folders the app replaces when it unpacks a new version
    outputDir.file("game-files.txt").get().asFile.writeText(GAME_FILE_DIRS.joinToString("\n"))
  }

  companion object {
    val GAME_FILE_DIRS =
      listOf(
        "game/assets",
        "game/graphics/opengl_renderer/shaders",
        "custom_assets",
        "goal_src/jak2/tools",
      )
  }
}

val stageGameFiles =
  tasks.register<StageGameFiles>("stageGameFiles") {
    repoRoot.set(layout.projectDirectory.dir("../.."))
    sources.from(StageGameFiles.GAME_FILE_DIRS.map { repoRoot.dir(it) })
  }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stageGameFiles, StageGameFiles::outputDir)
  }
}

android {
  namespace = "dev.sargunv.opengoal"
  compileSdk = 36
  // Keep in sync with ANDROID_NDK_VERSION in mise.toml.
  ndkVersion = "30.0.16248370"

  defaultConfig {
    applicationId = "dev.sargunv.opengoal"
    minSdk = 28
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    ndk { abiFilters += listOf("arm64-v8a", "x86_64") }

    externalNativeBuild {
      cmake {
        targets += "gk"
        arguments +=
          listOf(
            "-DANDROID_STL=c++_static",
            "-DSTATICALLY_LINK=true",
            "-DZYDIS_BUILD_SHARED_LIB=OFF",
          )
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = repoRoot.resolve("CMakeLists.txt")
      // The CMake on PATH from mise.toml
      version = "4.5.1"
    }
  }

  sourceSets {
    named("main") {
      // SDL's Java half, from the same vendored SDL that libmain.so links
      java.srcDir(repoRoot.resolve("third-party/SDL/android-project/app/src/main/java"))
    }
  }

  buildTypes {
    // Development builds only for now, so release builds use the debug key too.
    named("release") { signingConfig = signingConfigs.getByName("debug") }
  }

  packaging { jniLibs { useLegacyPackaging = false } }
}
