FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY check-migrations.sh .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-jammy

# Static ffmpeg build instead of `apt-get install ffmpeg`: apt pulls ~30
# transitive packages through dpkg (audio/video codec libs, X11 libs, etc.),
# which is a lot of small filesystem writes for something we only need one
# binary from. A single static tarball is faster, smaller, and avoids piling
# more dpkg transactions on top of any host-side disk/storage issues.
# TARGETARCH is set automatically by Docker/buildx (amd64 on most machines,
# arm64 on Apple Silicon Macs) so this works on both without extra flags.
ARG TARGETARCH
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates xz-utils curl \
    && ARCH_NAME=$([ "$TARGETARCH" = "arm64" ] && echo "arm64" || echo "amd64") \
    && curl -fsSL -o /tmp/ffmpeg.tar.xz \
       "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-${ARCH_NAME}-static.tar.xz" \
    && mkdir -p /tmp/ffmpeg-extract \
    && tar -xJf /tmp/ffmpeg.tar.xz -C /tmp/ffmpeg-extract --strip-components=1 \
    && mv /tmp/ffmpeg-extract/ffmpeg /tmp/ffmpeg-extract/ffprobe /usr/local/bin/ \
    && chmod +x /usr/local/bin/ffmpeg /usr/local/bin/ffprobe \
    && apt-get purge -y xz-utils curl \
    && apt-get autoremove -y \
    && rm -rf /tmp/ffmpeg* /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# Both storage roots created up front so a missing directory is never the
# reason a first upload or first render fails.
RUN mkdir -p /data/projects /data/video-editor
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
