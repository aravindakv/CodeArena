/**
 * One place for every version the build uses (Gradle 9.1, Java 25).
 * The Spring lines must stay compatible:
 *   Spring Boot 4.0.x  <->  Spring Cloud 2025.1.x (Oakwood)  <->  Spring Cloud AWS 4.0.x
 * Spring Cloud AWS documents support for Boot 4.0.x only, which is why this is not on Boot 4.1 yet.
 */
object Versions {
    const val JAVA = 25
    const val SPRING_BOOT = "4.0.9"
    const val SPRING_CLOUD = "2025.1.3"
    const val SPRING_CLOUD_AWS = "4.0.2"
    const val JMUSTACHE = "1.16"
    const val ULID = "5.2.3"
    const val RESILIENCE4J = "2.3.0"
}