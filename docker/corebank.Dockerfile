# Build with the same JBang version used for local development.
FROM eclipse-temurin:21-jdk-jammy AS build
ARG JBANG_VERSION=0.142.0
ADD https://github.com/jbangdev/jbang/releases/download/v${JBANG_VERSION}/jbang-${JBANG_VERSION}.tar /tmp/jbang.tar
RUN mkdir /opt/jbang && tar -xf /tmp/jbang.tar -C /opt/jbang --strip-components=1 && rm /tmp/jbang.tar
WORKDIR /workspace
COPY corebank/src/ corebank/src/
COPY corebank/application.yaml corebank/application.yaml
COPY postgres/changelog/ postgres/changelog/
RUN mkdir /export && /opt/jbang/bin/jbang export portable --output=/export/corebank.jar corebank/src/Core.java

# JBang, the compiler, sources, and tests stay outside the runtime image.
FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 10001 corebank && useradd --uid 10001 --gid corebank --no-create-home corebank \
    && mkdir -p /app /data/input /data/output /data/error \
    && chown -R corebank:corebank /app /data
WORKDIR /app
COPY --from=build --chown=corebank:corebank /export/ /app/
USER corebank
ENTRYPOINT ["java", "-jar", "/app/corebank.jar"]
