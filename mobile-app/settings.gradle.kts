// STOCK KMP app (ADR-0003): its own build, separate from the monorepo.
// The domain is consumed as an included build, with substitution by coordinate: it is the same
// instance of each rule that the API uses, not a copy (ADR-0003, D6).
pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mobile-app"

includeBuild("..") {
    dependencySubstitution {
        substitute(module("dev.axzy.stock:stock-domain")).using(project(":stock-domain"))
    }
}

include(":shared")
include(":androidApp")
