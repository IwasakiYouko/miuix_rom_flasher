rootProject.name = "MiuixWinSample"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

includeBuild("miuix-mingw-ref")

pluginManagement {
    repositories {
        mavenLocal()
        maven {
            name = "compose-mingw-maven-repository"
            setUrl("https://raw.githubusercontent.com/YuKongA/compose-mingw_maven-repository/main/repository/releases")
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        maven {
            name = "compose-mingw-maven-repository"
            setUrl("https://raw.githubusercontent.com/YuKongA/compose-mingw_maven-repository/main/repository/releases")
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":app")
