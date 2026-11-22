pluginManagement {
    repositories {
        google()
        val mirror = System.getenv("LIFTZIP_MAVEN_CENTRAL_MIRROR")
        if (mirror.isNullOrBlank()) mavenCentral() else maven { url = uri(mirror) }
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        val mirror = System.getenv("LIFTZIP_MAVEN_CENTRAL_MIRROR")
        if (mirror.isNullOrBlank()) mavenCentral() else maven { url = uri(mirror) }
    }
}
rootProject.name = "LiftZipMail"
include(":app")
