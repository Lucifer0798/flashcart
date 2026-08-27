# One Dockerfile for all eight modules, selected by --build-arg SERVICE.
#
# Eight near-identical Dockerfiles would drift apart the first time one of them needed a fix. The
# reactor is built once here and every service image is a thin final stage over the same layers.

# --- build ------------------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Poms first, on their own layer. Dependency resolution is the slow part of this build, and it only
# needs to re-run when a pom changes — not on every source edit.
COPY pom.xml .
COPY services/common/pom.xml     services/common/pom.xml
COPY services/gateway/pom.xml    services/gateway/pom.xml
COPY services/catalog/pom.xml    services/catalog/pom.xml
COPY services/order/pom.xml      services/order/pom.xml
COPY services/user/pom.xml       services/user/pom.xml
COPY services/payment/pom.xml    services/payment/pom.xml
COPY services/inventory/pom.xml  services/inventory/pom.xml
COPY services/shipping/pom.xml   services/shipping/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DskipTests

COPY services services
# Tests are not run here on purpose: the image build is not the place to discover a red suite, and
# the catalog's integration test needs a Docker daemon this build does not have.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# --- runtime ----------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

ARG SERVICE
ARG SERVER_PORT=8080

# Never root. A container escape is worth much less to an attacker who lands as an unprivileged user.
RUN addgroup -S flashcart && adduser -S flashcart -G flashcart
USER flashcart:flashcart

WORKDIR /app
COPY --from=build --chown=flashcart:flashcart /workspace/services/${SERVICE}/target/*.jar app.jar

ENV SERVER_PORT=${SERVER_PORT}
# MaxRAMPercentage rather than a fixed -Xmx: the JVM then sizes itself from the container's actual
# cgroup limit, so raising the compose/Kubernetes memory limit is enough to give the heap more room.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE ${SERVER_PORT}

# exec form via sh -c so JAVA_OPTS expands, and `exec` so the JVM is PID 1 and receives SIGTERM
# directly — which is what makes graceful shutdown actually work.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
