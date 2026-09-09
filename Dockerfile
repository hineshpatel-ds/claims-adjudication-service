# Multi-stage build.
#
# The build stage carries a full JDK, Maven, and the whole dependency tree -
# roughly 700MB of things that exist only to produce a JAR. Shipping that to
# production would mean shipping a compiler and a package manager to a machine
# that needs neither: a larger attack surface, slower pulls, and more to patch.
#
# The runtime stage starts fresh from a JRE and copies in only the built
# application.

# ---------------------------------------------------------------------------
# Stage 1: build
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build

# Copy the Maven wrapper and POM first, on their own.
#
# Docker caches each layer and invalidates everything after the first change.
# Dependencies change rarely; source changes constantly. Resolving dependencies
# in its own layer means an ordinary code edit reuses the cached download instead
# of re-fetching the tree - minutes per build, every build.
COPY mvnw .
COPY .mvn/ .mvn/
COPY pom.xml .

RUN chmod +x mvnw && ./mvnw -B dependency:go-offline

# Now the source. Everything below this line re-runs when any file changes.
COPY src/ src/

# Tests are deliberately skipped here.
#
# They need Docker (Testcontainers), and running Docker inside a Docker build is
# awkward and slow. Tests belong in CI, which runs them once against a real
# daemon before anything is built or published. Building an image is packaging,
# not verification - and an image built from unverified code should simply never
# reach the registry, which is CI's job to enforce.
RUN ./mvnw -B clean package -DskipTests

# Unpack the fat JAR into layers ordered by how often they change: dependencies
# (rarely), spring-boot-loader, snapshot dependencies, then the application
# itself (every commit). Copied as separate Docker layers, a code change
# re-uploads ~64KB instead of a 60MB fat JAR.
#
# What this actually produces on Boot 4 - checked, not assumed:
#
#   application/claims-service-0.0.1-SNAPSHOT.jar   a thin, runnable JAR
#   dependencies/lib/*.jar                          82 library JARs
#   spring-boot-loader/                             empty in this format
#   snapshot-dependencies/                          empty (no SNAPSHOT deps)
#
# The thin JAR's manifest carries `Class-Path: lib/...`, resolved relative to the
# JAR's own directory - which is why flattening every layer into /app is correct
# rather than sloppy: it puts lib/ exactly where the JAR expects it.
RUN java -Djarmode=tools -jar target/*.jar extract --layers --destination extracted

# Rename to a fixed filename so the entrypoint does not have to know the version.
# Hardcoding claims-service-0.0.1-SNAPSHOT.jar would break on the next release,
# and a glob in the entrypoint fails confusingly if a second JAR ever appears.
RUN mv extracted/application/*.jar extracted/application/app.jar

# ---------------------------------------------------------------------------
# Stage 2: runtime
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre

# curl is here so the container can answer a health check. It is a deliberate
# trade: a distroless image would be smaller and expose no shell or tools to
# anyone who gets in, but then neither Compose nor a plain `docker run` could
# check health without external tooling. In Kubernetes the probe is made by the
# kubelet from outside and this would not be needed.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# Run as a non-root user.
#
# Containers run as root by default. A process that only needs to read its own
# JAR and open a socket has no reason to be root, and if the application is
# compromised the difference decides whether an attacker can write to a mounted
# volume or install anything.
RUN groupadd --system --gid 1001 claims \
 && useradd --system --uid 1001 --gid claims --create-home claims

WORKDIR /app

# Copy layers in order of how often they change, so Docker caches the stable ones.
COPY --from=builder --chown=claims:claims /build/extracted/dependencies/ ./
COPY --from=builder --chown=claims:claims /build/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=claims:claims /build/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=claims:claims /build/extracted/application/ ./

USER claims

EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx.
#
# The JVM sizes its heap from the container's memory limit, so a percentage
# adapts when the limit changes instead of needing the flag updated in lockstep.
# 75% leaves headroom for metaspace, thread stacks, and native buffers, which live
# outside the heap and are what actually get a container OOM-killed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# ExitOnOutOfMemoryError above matters: by default a JVM that runs out of heap
# limps on, half-working, serving errors. Exiting lets the orchestrator restart
# it, which is almost always the better outcome.

HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://localhost:8080/actuator/health || exit 1

# `exec` matters. Without it, sh stays alive as PID 1 and does not forward
# SIGTERM, so on `docker stop` the JVM never receives the signal, never shuts
# down gracefully, and is killed after the timeout with in-flight requests
# dropped. With exec, the JVM replaces the shell and becomes PID 1 itself.
#
# sh -c rather than a pure exec-form ENTRYPOINT because $JAVA_OPTS has to be
# expanded by a shell; exec form does no variable substitution.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
