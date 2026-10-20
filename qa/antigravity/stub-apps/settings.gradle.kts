// Stand-in apps for Guardian Mode testing. Each flavor installs a tiny, harmless app under a
// real watched package name (a bank, a payment app, a remote-control app) so the tripwires can
// be tested without installing the real thing. Test devices only; never publish these.
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

rootProject.name = "guardian-stub-apps"
include(":app")
