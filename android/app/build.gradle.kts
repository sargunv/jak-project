import javax.inject.Inject

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// The repository root holds the CMake project that builds the game runtime (libmain.so).
val repoRoot = rootDir.parentFile

/**
 * Stages the files from this repository that the app needs on the device, in the layout of the
 * repository, which is the layout of the runtime's data folder. The app ships them as assets under
 * `game-files/` and unpacks them (GameFiles.kt): the runtime's own files into its data folder, and
 * the extractor's into a work folder while it installs a game from its ISO.
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
      // goal_src/jak2 holds goal_src/jak2/tools
      duplicatesStrategy = DuplicatesStrategy.EXCLUDE
      for (path in RUNTIME_FILES + BUILD_FILES.values.flatten()) {
        val source = root.file(path).asFile
        if (source.isDirectory) {
          from(source) { into(path) }
        } else {
          from(source) { into(source.parentFile.relativeTo(root.asFile).path) }
        }
      }
    }
    // The paths the app unpacks: the runtime's on every app update, the extractor's per game
    outputDir.file("game-files.txt").get().asFile.writeText(RUNTIME_FILES.joinToString("\n"))
    outputDir
      .file("build-files.txt")
      .get()
      .asFile
      .writeText(
        BUILD_FILES.flatMap { (game, paths) -> paths.map { "$game $it" } }.joinToString("\n"),
      )
  }

  companion object {
    /** What the runtime reads, besides the game's data in out/. */
    val RUNTIME_FILES =
      listOf(
        "game/assets",
        "game/graphics/opengl_renderer/shaders",
        "custom_assets",
        "goal_src/jak2/tools",
      )

    /**
     * What the extractor reads, besides the ISO and the runtime's files, by game (`*` for all). It
     * decompiles with the decompiler's config and compiles the game's GOAL code.
     */
    val BUILD_FILES =
      mapOf(
        "*" to listOf("goal_src/common", "goal_src/goal-lib.gc", "goal_src/goos-lib.gs"),
        "jak1" to listOf("goal_src/jak1", "decompiler/config/jak1"),
        // Jak II and Jak 3 also compile Jak 1's PC kernel (cgo-file in their lib/project-lib.gp)
        "jak2" to listOf("goal_src/jak2", "goal_src/jak1/pc", "decompiler/config/jak2"),
        "jak3" to listOf("goal_src/jak3", "goal_src/jak1/pc", "decompiler/config/jak3"),
      )
  }
}

val stageGameFiles =
  tasks.register<StageGameFiles>("stageGameFiles") {
    repoRoot.set(layout.projectDirectory.dir("../.."))
    sources.from(
      (StageGameFiles.RUNTIME_FILES + StageGameFiles.BUILD_FILES.values.flatten()).map {
        repoRoot.file(it)
      },
    )
  }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stageGameFiles, StageGameFiles::outputDir)
  }
}

android {
  namespace = "dev.sargunv.opengoal"
  // Compose 1.11 and Material 3 1.5 need 37.1 or later
  compileSdk { version = release(37) { minorApiLevel = 2 } }
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
        // libmain.so (the runtime), libextractor.so (an executable, see decompiler/CMakeLists.txt),
        // and libog_process.so, which runs it
        targets += listOf("gk", "extractor", "og_process")
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

  buildFeatures { compose = true }

  buildTypes {
    // Development builds only for now, so release builds use the debug key too.
    named("release") { signingConfig = signingConfigs.getByName("debug") }
  }

  // Android can only run the extractor from the native library folder on disk, so native code is
  // unpacked from the APK at install time.
  packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.material3)
  implementation(libs.compose.ui.tooling.preview)
  implementation(libs.activity.compose)
  implementation(libs.lifecycle.runtime.compose)
  implementation(libs.lifecycle.viewmodel.compose)
  debugImplementation(libs.compose.ui.tooling)
  testImplementation(libs.junit)
}

// InstallLogTest checks the extractor's source for the log lines that the installer follows
tasks.withType<Test>().configureEach { systemProperty("repoRoot", repoRoot.path) }
