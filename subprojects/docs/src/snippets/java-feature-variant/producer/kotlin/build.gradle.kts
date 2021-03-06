// tag::plugins[]
plugins {
    `java-library`
    `maven-publish`
}
// ...
// end::plugins[]

repositories {
    jcenter()
}

// tag::register_variant[]
group = "org.gradle.demo"
version = "1.0"

java {
    registerFeature("mongodbSupport") {
        usingSourceSet(sourceSets["main"])
    }
}
// end::register_variant[]

// tag::variant_dependencies[]
dependencies {
    "mongodbSupportImplementation"("org.mongodb:mongodb-driver-sync:3.9.1")
}
// end::variant_dependencies[]


// tag::publishing[]
publishing {
    publications {
        create("myLibrary", MavenPublication::class.java) {
            from(components["java"])
        }
    }
}
// end::publishing[]
