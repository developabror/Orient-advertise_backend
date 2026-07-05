# syntax=docker/dockerfile:1.6
#
# Layered, cache-aware build for the Spring Boot api module.
#
# Build performance strategy (in priority order):
#   1. BuildKit cache mounts on /root/.gradle survive across builds — the Gradle
#      distribution (~150 MB) and every Maven dependency download once and stay cached
#      on the host. A second build with no changes finishes in ~30 s instead of 7 min.
#   2. Layer ordering: gradle wrapper → root + module build.gradle files → sources.
#      Source-only edits invalidate only the final RUN, leaving the dep-resolution
#      layer cached.
#   3. `.dockerignore` excludes .gradle/, build/, .git/, IDE files so the COPY context
#      stays small even on a fully-built workspace.
#   4. Tests skipped in Docker (`-x test`). The Docker build produces the deployable
#      artifact; CI runs the test suite separately.
#
# Required: BuildKit (default in recent Docker versions). Enable with
# `DOCKER_BUILDKIT=1` if running on an old daemon.

############################################
# Stage 1 — build the boot jar
############################################
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# --- Layer A: Gradle wrapper. Invalidates only when the wrapper version changes. ---
COPY gradlew gradlew.bat ./
COPY gradle gradle

# --- Layer B: build files. Invalidates only when dependencies are added/removed. ---
COPY settings.gradle build.gradle gradle.properties ./
COPY common/build.gradle  common/build.gradle
COPY domain/build.gradle  domain/build.gradle
COPY infra/build.gradle   infra/build.gradle
COPY service/build.gradle service/build.gradle
COPY api/build.gradle     api/build.gradle

# Pre-fetch Gradle distribution + project dependencies into the cache mount. The
# `|| true` swallows non-fatal "no source" warnings when this runs against a tree
# without sources yet — the goal is just to populate the cache, not to produce
# artifacts. Subsequent builds skip this almost entirely thanks to the cache mount.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :api:dependencies --quiet || true

# --- Layer C: sources. Invalidates on every code change — but the layers above
#               stay cached, so the rebuild only pays for compile + bootJar. ---
COPY common/src   common/src
COPY domain/src   domain/src
COPY infra/src    infra/src
COPY service/src  service/src
COPY api/src      api/src

# Build the boot jar. The cache mount means the Gradle distribution + Maven cache are
# already warm — only the compile/jar tasks actually run. `-x test` skips the test
# suite (run separately in CI). The final `cp` produces a known artifact name so the
# runtime stage doesn't have to glob across the boot-vs-plain jar variants.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :api:bootJar -x test \
 && cp api/build/libs/api-*[0-9].jar /app/app.jar

############################################
# Stage 2 — slim runtime
############################################
FROM eclipse-temurin:21-jre

# ffmpeg + ffprobe (bundled in the same Debian package) are required by
# FFmpegTranscoder / FFprobeVideoInspector — they shell out to these binaries
# for content transcoding and metadata probing. Without them, every upload
# completes the bytes-to-MinIO step but the async transcode fails with
# "Cannot run program 'ffmpeg': No such file or directory" and content stays
# stuck in UPLOADED state forever.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY --from=build /app/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
