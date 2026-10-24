FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline
COPY src src
RUN mvn --batch-mode package -DskipTests

FROM eclipse-temurin:21-jdk-jammy AS runtime

RUN $JAVA_HOME/bin/jlink \
    --add-modules java.base,java.logging,java.sql,jdk.httpserver,jdk.unsupported \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --output /runtime

FROM debian:bookworm-slim

WORKDIR /app
RUN groupadd --gid 10001 patria \
    && useradd --uid 10001 --gid patria --create-home patria \
    && mkdir /data \
    && chown patria:patria /data
COPY --from=runtime /runtime /opt/java
COPY --from=build --chown=patria:patria /build/target/patria.jar /app/patria.jar

USER patria
EXPOSE 8080
ENTRYPOINT ["/opt/java/bin/java", "-Xms16m", "-Xmx96m", "-jar", "/app/patria.jar"]
