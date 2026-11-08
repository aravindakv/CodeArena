
// Root build: everything lives in the convention plugins under build-logic/.
// Useful tasks:
//   ./gradlew build              compile + test every module
//   ./gradlew bootJar            build runnable jars (scripts/services.sh uses these)
tasks.register("printModules") {
    doLast { subprojects.forEach { println(it.path) } }
}