plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jifa-bridge"

include(
    "common",
    "analysis",
    "gc-log",
    "jfr",
    "thread-dump",
    "heap-dump-api",
    "heap-dump-hook",
    "heap-dump-impl",
    "heap-dump-provider",
    "heap-dump-eclipse-mat-deps",
)

project(":common").projectDir = file("../jifa/common")
project(":analysis").projectDir = file("../jifa/analysis")
project(":gc-log").projectDir = file("../jifa/analysis/gc-log")
project(":jfr").projectDir = file("../jifa/analysis/jfr")
project(":thread-dump").projectDir = file("../jifa/analysis/thread-dump")
project(":heap-dump-api").projectDir = file("../jifa/analysis/heap-dump/api")
project(":heap-dump-hook").projectDir = file("../jifa/analysis/heap-dump/hook")
project(":heap-dump-impl").projectDir = file("../jifa/analysis/heap-dump/impl")
project(":heap-dump-provider").projectDir = file("../jifa/analysis/heap-dump/provider")
project(":heap-dump-eclipse-mat-deps").projectDir = file("../jifa/analysis/heap-dump/eclipse-mat-deps")
