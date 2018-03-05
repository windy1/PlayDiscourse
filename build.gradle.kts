plugins {
    scala
    `maven-publish`
}

repositories {
    mavenCentral()
}

val playVersion = "2.6.12"

dependencies {
    compile("org.scala-lang", "scala-library", "2.12.4")
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

val spongeRepo by project
val spongeUser by project
val spongePassword by project

publishing {
    spongeRepo?.let { repo ->
        repositories {
            maven(repo) {
                if (spongeUser != null && spongePassword != null) {
                    credentials {
                        username = spongeUser?.toString()
                        password = spongePassword?.toString()
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
