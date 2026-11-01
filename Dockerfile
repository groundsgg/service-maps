FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_USER

WORKDIR /workspace

# copy "static" files first to improve layer caching
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/ src/
COPY lit-worker/ lit-worker/

# Use locked caches to avoid corruption on parallel builds
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    --mount=type=secret,id=github_token,required=true \
    --mount=type=secret,id=gradle_init,target=/root/.gradle/init.d/grounds-maven-mirror.init.gradle.kts,required=false \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon --stacktrace \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
        quarkusBuild :lit-worker:installDist -x test \
    '

FROM gcr.io/distroless/java25-debian13 AS runtime

WORKDIR /deployments/quarkus-app

USER 65532:65532

COPY --from=build --chown=nonroot:nonroot /workspace/build/quarkus-app/ ./
# The lit worker: a plain JVM program with Minestom, run as a Job from this same image. Kept out
# of the service's classpath, whose Quarkus platform pins libraries Minestom needs newer.
COPY --from=build --chown=nonroot:nonroot /workspace/lit-worker/build/install/lit-worker/ /deployments/lit-worker/

EXPOSE 9000
# The default remains the service; callers can override CMD with the standalone worker command.
ENTRYPOINT ["java"]
CMD ["-jar", "quarkus-run.jar"]
