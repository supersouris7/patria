FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline
COPY src src
RUN mvn --batch-mode package -DskipTests

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
RUN groupadd --gid 10001 patria \
    && useradd --uid 10001 --gid patria --create-home patria \
    && mkdir /data \
    && chown patria:patria /data
COPY --from=build --chown=patria:patria /build/target/patria.jar /app/patria.jar

USER patria
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/patria.jar"]
