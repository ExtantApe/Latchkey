plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.extantape"
version = "0.4.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val paperApi = "io.papermc.paper:paper-api:26.1.2.build.74-stable"

dependencies {
    compileOnly(paperApi)
    implementation("com.github.ExtantApe:Signalbox:v0.3.0")

    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("paper-plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("Latchkey")
}

// Signalbox goes inside the jar, relocated, so it can never clash with another plugin's copy.
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("com.extantape.signalbox", "com.extantape.latchkey.libs.signalbox")
}
tasks.jar { archiveClassifier.set("plain") }
tasks.build { dependsOn(tasks.shadowJar) }

// A test-only plugin that logs the lobby events, for test/lobby.mjs. Never shipped.
val spy: SourceSet = sourceSets.create("spy") {
    java.srcDir("test/spy/java")
    resources.srcDir("test/spy/resources")
    compileClasspath += sourceSets.main.get().output
}
dependencies { "spyCompileOnly"(paperApi) }
tasks.register<Jar>("spyJar") {
    from(spy.output)
    archiveFileName.set("LatchkeySpy.jar")
    destinationDirectory.set(layout.buildDirectory.dir("test-spy"))
}
