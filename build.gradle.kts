plugins {
    scala
    `maven-publish`
}

repositories {
    mavenCentral()
}

val playVersion = "2.6.17"

dependencies {
    compile("org.scala-lang", "scala-library", "2.12.6")
    compile("com.typesafe.play", "play_2.12", playVersion)
    compile("com.typesafe.play", "play-ws_2.12", playVersion)
}

val sourceJar = task<Jar>("sourceJar") {
    classifier = "sources"
    from(java.sourceSets["main"].allSource)
}

val scaladoc: ScalaDoc by tasks
val javadocJar = task<Jar>("javadocJar") {
    classifier = "javadoc"
    dependsOn(scaladoc)
    from(scaladoc.destinationDir)
}

val spongeRepo: String? by project
val spongeUsername: String? by project
val spongePassword: String? by project

publishing {
    spongeRepo?.let { repo ->
        repositories {
            maven(repo) {
                if (spongeUsername != null && spongePassword != null) {
                    credentials {
                        username = spongeUsername
                        password = spongePassword
                    }
                }
            }
        }
    }

    (publications) {
        "mavenJava"(MavenPublication::class) {
            from(components["java"])
            artifact(sourceJar)
            artifact(javadocJar)
        }
    }
}
