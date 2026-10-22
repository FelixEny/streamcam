pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "StreamCam"

include(":app")
include(":core:common")
include(":core:camera")
include(":core:imageproc")
include(":core:encoder")
include(":transport:rtsp")
include(":thirdparty:rtspserver")
