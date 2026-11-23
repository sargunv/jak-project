plugins { alias(libs.plugins.android.application) }

// The repository root holds the CMake project that builds the game runtime (libmain.so).
val repoRoot = rootDir.parentFile

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
