FROM eclipse-temurin:25-jdk AS build

ARG GITHUB_USER

WORKDIR /workspace

# copy "static" files first to improve layer caching
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
COPY src/ src/

# Use locked caches to avoid corruption on parallel builds
RUN --mount=type=cache,target=/root/.gradle/caches,sharing=locked \
    --mount=type=cache,target=/root/.gradle/wrapper,sharing=locked \
    --mount=type=secret,id=github_token,required=true \
    /bin/sh -euc '\
      : "${GITHUB_USER:?GITHUB_USER build arg is required}"; \
      token="$(cat /run/secrets/github_token)"; \
      ./gradlew --no-daemon --stacktrace \
        -Pgithub.user="${GITHUB_USER}" \
        -Pgithub.token="${token}" \
        quarkusBuild -x test \
    '

FROM gcr.io/distroless/java25-debian13 AS runtime

WORKDIR /deployments/quarkus-app

USER 65532:65532

COPY --from=build --chown=nonroot:nonroot /workspace/build/quarkus-app/ ./

EXPOSE 9000
# The default remains the service; callers can override CMD with the standalone worker command.
ENTRYPOINT ["java"]
CMD ["-jar", "quarkus-run.jar"]
