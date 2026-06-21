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
    }
}

rootProject.name = "EmusitePlugin"

include(":plugin-api")
include(":vixsrc-plugin")
include(":onlineserietv-plugin")
include(":guardaserie-plugin")
