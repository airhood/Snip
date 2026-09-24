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
        // jlatexmath-android (LaTeX rendering for Markwon) is only published here.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "Snip"
include(":app")
