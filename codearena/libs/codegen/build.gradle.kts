plugins { id("codearena.java-library") }

dependencies {
    implementation("com.samskivert:jmustache:${Versions.JMUSTACHE}")
    api("com.fasterxml.jackson.core:jackson-annotations")   // unchanged in Jackson 3
}