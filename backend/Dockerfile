FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY check-migrations.sh .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Static ffmpeg/ffprobe from a multi-arch image (amd64 + arm64). Replaces the johnvansickle.com download,
# which returned a non-xz page and broke the build ("xz: File format not recognized").
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM eclipse-temurin:21-jre-jammy
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
RUN apt-get update \
    && apt-get install -y --no-install-recommends fontconfig libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Both storage roots created up front so a missing directory is never the
# reason a first upload or first render fails.
RUN mkdir -p /data/projects /data/video-editor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
