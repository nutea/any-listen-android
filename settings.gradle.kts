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
        maven("https://maven.aliyun.com/repository/public") {
            content { includeModule("com.github.promeg", "tinypinyin") }
        }
    }
}

rootProject.name = "any-listen-android"
include(":app")
include(":core:model")
include(":core:data")
include(":core:playback")
