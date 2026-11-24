import java.security.MessageDigest
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
    // Each game's data version: a hash of what its installed data comes from. When an update
    // changes it, the launcher asks to install the game again.
    val shared = hashFiles(root.asFile, DATA_SOURCES, exclude = "decompiler/config/")
    outputDir
      .file("data-versions.txt")
      .get()
      .asFile
      .writeText(
        BUILD_FILES.keys
          .filter { it != "*" }
          .joinToString("\n") { game ->
            val files =
              hashFiles(root.asFile, BUILD_FILES.getValue("*") + BUILD_FILES.getValue(game))
            "$game ${sha256(shared + files).take(16)}"
          },
      )
  }

  private fun hashFiles(root: File, paths: List<String>, exclude: String? = null): String {
    val digest = MessageDigest.getInstance("SHA-256")
    paths
      .map { root.resolve(it) }
      .flatMap { it.walk().filter(File::isFile).toList() }
      .map { it.relativeTo(root).invariantSeparatorsPath to it }
      .filter { (path, _) -> exclude == null || !path.startsWith(exclude) }
      .sortedBy { it.first }
      .forEach { (path, file) ->
        digest.update(path.toByteArray())
        digest.update(file.readBytes())
      }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun sha256(text: String) =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
      "%02x".format(it)
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

    /**
     * What else decides a game's installed data: the decompiler, the compiler, and the formats and
     * versions they share with the runtime. Changes to the runtime alone don't need new data.
     */
    val DATA_SOURCES =
      listOf("decompiler", "goalc", "common/custom_data", "common/versions", "common/goos")
  }
}

val stageGameFiles =
  tasks.register<StageGameFiles>("stageGameFiles") {
    repoRoot.set(layout.projectDirectory.dir("../.."))
    sources.from(
      (StageGameFiles.RUNTIME_FILES +
          StageGameFiles.BUILD_FILES.values.flatten() +
          StageGameFiles.DATA_SOURCES)
        .map { repoRoot.file(it) },
    )
  }

androidComponents {
  onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(stageGameFiles, StageGameFiles::outputDir)
  }
}

android {
  namespace = "dev.sargunv.pocketgoal"
  // Compose 1.11 and Material 3 1.5 need 37.1 or later
  compileSdk { version = release(37) { minorApiLevel = 2 } }
  // Keep in sync with ANDROID_NDK_VERSION in mise.toml.
  ndkVersion = "30.0.16248370"

  defaultConfig {
    applicationId = "dev.sargunv.pocketgoal"
    minSdk = 28
    targetSdk = 36
    // CI passes the version: the tag's for releases, and the commit count as the code, which grows
    // with every commit on the branch, so each build installs over the last
    versionCode = providers.environmentVariable("POCKETGOAL_VERSION_CODE").orNull?.toInt() ?: 1
    versionName = providers.environmentVariable("POCKETGOAL_VERSION_NAME").orNull ?: "0.0.0-dev"

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
        // A compiler cache, such as CI's sccache
        providers.environmentVariable("POCKETGOAL_COMPILER_LAUNCHER").orNull?.let {
          arguments +=
            listOf("-DCMAKE_C_COMPILER_LAUNCHER=$it", "-DCMAKE_CXX_COMPILER_LAUNCHER=$it")
        }
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

  // Release builds sign with the release key when CI provides it (POCKETGOAL_KEYSTORE, a path, and
  // the passwords), and with the debug key otherwise, for local testing.
  val releaseKeystore = providers.environmentVariable("POCKETGOAL_KEYSTORE").orNull
  signingConfigs {
    if (releaseKeystore != null) {
      create("release") {
        storeFile = file(releaseKeystore)
        storePassword = providers.environmentVariable("POCKETGOAL_KEYSTORE_PASSWORD").get()
        keyAlias = providers.environmentVariable("POCKETGOAL_KEY_ALIAS").get()
        keyPassword = providers.environmentVariable("POCKETGOAL_KEY_PASSWORD").get()
      }
    }
  }

  buildTypes {
    named("release") {
      signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
      // Upstream's Release, as its desktop presets use. Gradle's default, RelWithDebInfo, gets a
      // Windows-only linker flag from the top-level CMakeLists.
      externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } }
    }
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
