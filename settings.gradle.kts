rootProject.name = "Latchkey"

// Signalbox is built from the local checkout until its repo is public (owner step N48). Then this goes, and the
// dependency in build.gradle.kts resolves through JitPack instead. No token.
includeBuild("../Signalbox") {
    dependencySubstitution {
        substitute(module("com.github.ExtantApe:Signalbox")).using(project(":"))
    }
}
