pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "aeron-artio-fix"

// Build order follows docs/00-implementation-plan.md section 3.
include("fix-dictionary")          // QuickFIX/J XML -> Artio XML converter
include("fix-codecs")              // generated Artio codecs for FIX 4.2 / 4.4
include("artio-engine")            // Artio engine + library runtime, message sink SPI
include("quickfixj-counterparty")  // QuickFIX/J initiator/acceptor for tests and demos
include("amps-server")             // AMPS flow config, compose file, lifecycle script
include("amps-test-harness")       // throwaway AMPS via podman compose for integration suites
include("artio-amps-bridge")       // Artio -> AMPS publisher, plain-Java runnable
include("artio-spring-boot")       // the same runtime as a Spring Boot application
