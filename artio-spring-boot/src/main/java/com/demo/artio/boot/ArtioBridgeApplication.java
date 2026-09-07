package com.demo.artio.boot;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The Artio FIX engine and the AMPS bridge as a Spring Boot application.
 *
 * <pre>
 *   ./gradlew :artio-spring-boot:bootRun
 *   ./gradlew :artio-spring-boot:bootRun -Dartio.port=9881
 *   ./gradlew :artio-spring-boot:bootRun -Dspring.profiles.active=initiator
 *
 *   java --add-opens   java.base/jdk.internal.misc=ALL-UNNAMED \
 *        --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
 *        --add-opens   java.base/sun.nio.ch=ALL-UNNAMED \
 *        -jar artio-spring-boot/build/libs/artio-spring-boot-1.0.0.jar --artio.port=9881
 * </pre>
 *
 * <p><strong>No servlet stack.</strong> {@code spring-boot-starter} without {@code -web} means
 * {@code SpringApplication.deduceFromClasspath()} finds neither {@code jakarta.servlet.Servlet} nor
 * {@code ConfigurableWebApplicationContext} and returns {@link WebApplicationType#NONE}, so the
 * context is a plain {@code AnnotationConfigApplicationContext} and no port is opened but Artio's.
 * {@code ArtioBridgeApplicationTest} asserts that rather than trusting it; this class also says it
 * out loud, because "why is there no Tomcat here" is the first question a reader has.
 *
 * <p><strong>What keeps the JVM alive.</strong> Nothing in Spring: with no web server there is no
 * container thread to join. Artio's and the bridge's {@code AgentRunner} threads are non-daemon and
 * would do it, but relying on that would make "the process exits immediately" a possible
 * consequence of a change three modules away, so {@code spring.main.keep-alive} is set as a default
 * property here. It is set in {@code main} rather than in {@code application.yml} so that
 * {@code @SpringBootTest} contexts - which never call {@code main} - do not inherit a non-daemon
 * thread they would have to shut down.
 */
@SpringBootApplication
public class ArtioBridgeApplication
{
    /**
     * @param args standard Spring Boot arguments; {@code --artio.port=9881} and friends override
     *             anything in {@code application.yml}.
     */
    public static void main(final String[] args)
    {
        new SpringApplicationBuilder(ArtioBridgeApplication.class)
            // Implicit without a web starter; stated anyway, so adding a dependency that happens to
            // drag in spring-web cannot silently start a web server next to the FIX engine.
            .web(WebApplicationType.NONE)
            .properties("spring.main.keep-alive=true")
            .run(args);
    }
}
