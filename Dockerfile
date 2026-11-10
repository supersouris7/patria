# Compilation seulement : Maven et le JDK complet ne vont pas dans l'image finale.
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
COPY pom.xml .
RUN mvn --batch-mode dependency:go-offline
COPY src src
COPY LICENSE LICENSE.fr ./
# Commit affiche en pied de page (docker build --build-arg GIT_COMMIT=$(git rev-parse HEAD) .)
ARG GIT_COMMIT=dev
# Version affichee : le numero du tag stable (X.Y.Z) ; vide pour unstable, qui garde celle du pom.xml.
ARG APP_VERSION=
RUN mvn --batch-mode package -DskipTests -Dgit.commit=${GIT_COMMIT} ${APP_VERSION:+-Dapp.version=$APP_VERSION} \
    && mkdir -p /out/data /out/native \
    # Bibliotheque native de SQLite extraite ici : le conteneur peut tourner en lecture seule, /tmp non executable.
    && arch="$(uname -m)" \
    && cd /out/native && jar xf /build/target/patria.jar "org/sqlite/native/Linux/${arch}/libsqlitejdbc.so" \
    && mv "org/sqlite/native/Linux/${arch}/libsqlitejdbc.so" . && rm -rf org && test -s libsqlitejdbc.so

# Java reduit aux modules utiles (jlink) ; la derniere mise a jour de Java 21 est reprise a chaque construction.
FROM eclipse-temurin:21-jdk-jammy AS runtime

RUN $JAVA_HOME/bin/jlink \
    --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,java.xml,jdk.crypto.ec,jdk.httpserver,jdk.localedata,jdk.unsupported \
    --include-locales=fr,en \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --output /runtime

# Image finale minimale (Wolfi, Chainguard) : seulement la glibc et les certificats, ni shell ni gestionnaire de
# paquets ; reconstruite chaque jour avec les correctifs (amd64 et arm64). Figee par son empreinte, mise a jour par
# Dependabot (.github/dependabot.yml). L'utilisateur 10001 et le dossier /data restent ceux des versions precedentes
# (volumes existants).
FROM cgr.dev/chainguard/glibc-dynamic:latest@sha256:aab5cd0b4cc5edd9c87b6e7aa9ddaa3fb6ffcec559b9e5473c55bbc937b65399

# Fichiers du programme a root ; l'application tourne ensuite sous 10001, sans droit d'ecriture hors de /data et /tmp.
USER 0:0
WORKDIR /app
COPY --from=runtime /runtime /opt/java
COPY --from=build --chown=10001:10001 /out/data /data
COPY --from=build /out/native /app/native
# Programme et bibliotheque native appartiennent a root : l'application ne peut pas les modifier.
COPY --from=build /build/target/patria.jar /app/patria.jar

LABEL org.opencontainers.image.title="Patria" \
      org.opencontainers.image.vendor="William Iv" \
      org.opencontainers.image.licenses="Elastic-2.0"
ENV JAVA_HOME=/opt/java PATH="/opt/java/bin:/usr/bin:/bin"
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/java/bin/java", "-Djava.awt.headless=true", "-Duser.home=/data", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Dorg.sqlite.lib.path=/app/native", "-Dorg.sqlite.lib.name=libsqlitejdbc.so", "-Dsun.net.httpserver.maxReqTime=120", "-Xms16m", "-Xmx96m", "-jar", "/app/patria.jar"]
