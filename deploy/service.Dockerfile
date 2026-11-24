# Pass reviewed immutable JDK/JRE image digests from release configuration.
ARG BUILD_IMAGE
ARG RUNTIME_IMAGE
FROM ${BUILD_IMAGE} AS build
ARG SERVICE
WORKDIR /build
COPY services/${SERVICE}/pom.xml services/${SERVICE}/mvnw ./
COPY services/${SERVICE}/.mvn/ .mvn/
COPY services/${SERVICE}/src/ src/
RUN chmod 755 mvnw && ./mvnw -B -ntp -DskipTests package
COPY deploy/Healthcheck.java /tmp/Healthcheck.java
RUN javac --release 25 -d /tmp/health /tmp/Healthcheck.java
FROM ${RUNTIME_IMAGE}
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/*.jar /app/service.jar
COPY --from=build --chown=10001:10001 /tmp/health/ /app/health/
USER 10001:10001
ENV SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 CMD ["java","-cp","/app/health","Healthcheck"]
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-XX:+ExitOnOutOfMemoryError","-jar","/app/service.jar"]
