# Multi-stage: build with the full JDK + Maven Wrapper, run on a slim JRE.
#
# JDK 25, not any other version — matching pom.xml's <java.version>. mvnw's
# distributionType=only-script means it downloads its own Maven on first run, so nothing else
# needs to be preinstalled in the build stage.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /build

# Dependencies first so this layer is cached across source-only changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src ./src
RUN ./mvnw -B -q clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/ai-doc-qna-*.jar app.jar

# Render (and most PaaS hosts) assign the listen port via $PORT; application.yaml's
# server.port already reads it. -XX:MaxRAMPercentage keeps the JVM heap within a small
# container's actual memory instead of sizing off the host's full RAM.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
