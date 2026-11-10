plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.dsh.assistant"
version = "2.1.1"

repositories {
    mavenCentral()
}

dependencies {
    // Pure-Java zstd decoder for reading DSH session history (session.*.jsonl.zstd)
    implementation("io.airlift:aircompressor:0.27")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.slf4j:slf4j-api:2.0.9") // required by sqlite-jdbc 3.45+
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellij {
    version.set("2024.3.5")
    type.set("IC")
    plugins.set(listOf())
    downloadSources.set(false)
    updateSinceUntilBuild.set(false)
}

tasks {
    patchPluginXml {
        sinceBuild.set("243")
        untilBuild.set("253.*")
        changeNotes.set("""
            <h4>2.1.1</h4>
            <ul>
              <li><b>Changes and the History list appear right after the agent</b>: the watcher
                  polls project files every 0.3 s and wakes up on a DSH signal
                  (<code>workspace/changes</code>, turn start/end).</li>
              <li>The final file version is recorded in the history when the turn ends.</li>
            </ul>
            <h4>2.1.0</h4>
            <ul>
              <li><b>TAB completion</b>: gray DSH inline suggestions in the editor, Tab accepts.</li>
              <li>Esc hides the suggestion and repeats the request over the whole file after 3 seconds.</li>
              <li>The Extended TAB help option — large continuations up to 12,000 tokens.</li>
              <li>A dedicated DSH completion process without tools: answers in ~1–2 s and never touches files.</li>
            </ul>
            <h4>2.0.0</h4>
            <ul>
              <li>DSH web application embedded into the PyCharm tool window.</li>
              <li>Several independent DSH session windows.</li>
              <li>The Changes panel: diff and accept/reject of changed files, one by one or all at once.</li>
              <li>Settings in .dsh/pycharm-assistant.json, synchronized through DSH.</li>
            </ul>
        """.trimIndent())
    }

    buildPlugin {
        dependsOn("patchPluginXml")
    }
}
