plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "arthas-workbench"

check(file("jifa/settings.gradle").exists()) {
    "Jifa submodule is not initialized. Run `git submodule update --init --recursive` first."
}

includeBuild("jifa-bridge") {
    dependencySubstitution {
        substitute(module("jifa:common")).using(project(":common"))
        substitute(module("jifa:analysis")).using(project(":analysis"))
        substitute(module("jifa.analysis:gc-log")).using(project(":gc-log"))
        substitute(module("jifa.analysis:jfr")).using(project(":jfr"))
        substitute(module("jifa.analysis:thread-dump")).using(project(":thread-dump"))
        substitute(module("jifa.analysis:heap-dump-api")).using(project(":heap-dump-api"))
        substitute(module("jifa.analysis:heap-dump-impl")).using(project(":heap-dump-impl"))
        substitute(module("jifa.analysis:heap-dump-provider")).using(project(":heap-dump-provider"))
    }
}
