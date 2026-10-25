FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY check-migrations.sh .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

# Install FFmpeg from Ubuntu's maintained package repository. This avoids
# downloading a third-party static archive that may return HTML/error content
# instead of a valid .tar.xz file.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates fontconfig libfreetype6 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Both storage roots created up front so a missing directory is never the
# reason a first upload or first render fails.
RUN mkdir -p /data/projects /data/video-editor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
