plugins {
    application
}

group = "refinerymcp"
version = "0.1.0-dev"

val refineryVersion = "0.3.0"
val mcpSdkVersion = "2.1.0"

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    implementation(platform("tools.refinery:refinery-bom:$refineryVersion"))
    implementation("tools.refinery:refinery-generator")
    implementation("io.modelcontextprotocol.sdk:mcp:$mcpSdkVersion")

    runtimeOnly("org.slf4j:log4j-over-slf4j")
    runtimeOnly("org.slf4j:slf4j-simple")

    testImplementation(testFixtures("tools.refinery:refinery-language:$refineryVersion"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

application {
    mainClass.set("refinerymcp.Main")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("app.runtimeClasspath", sourceSets.main.get().runtimeClasspath.asPath)
}

tasks.processResources {
    val buildInfo = mapOf(
        "serverName" to project.name,
        "serverVersion" to project.version.toString(),
        "refineryVersion" to refineryVersion,
        "mcpSdkVersion" to mcpSdkVersion
    )
    inputs.properties(buildInfo)
    filesMatching("server-info.properties") {
        expand(buildInfo)
    }
}
