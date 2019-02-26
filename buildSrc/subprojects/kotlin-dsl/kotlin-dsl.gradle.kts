dependencies {

    implementation(project(":configuration"))
    implementation(project(":build"))

    api(kotlin("gradle-plugin"))
    api(kotlin("stdlib-jdk8"))
    api(kotlin("reflect"))
    api(kotlin("compiler-embeddable"))

    implementation("org.gradle.kotlin:gradle-kotlin-dsl-conventions:0.2.3")
    implementation("com.gradle.publish:plugin-publish-plugin:0.10.0")

    implementation("com.thoughtworks.qdox:qdox:2.0-M9")
    implementation("org.ow2.asm:asm:6.2.1")

    testImplementation("junit:junit:4.12")
    testImplementation("com.nhaarman:mockito-kotlin:1.6.0")
}
