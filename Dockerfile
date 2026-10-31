# The backend as a container, for docker-compose.ha.yml (RD-7) and docker-compose.vpn.yml (VP-4).
# Build the jar first:   mvn -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/telecom-ocs-0.0.1-SNAPSHOT.jar app.jar
# certificates are mounted at /certs at run time, never baked into the image
ENV TLS_DIR=file:/certs
EXPOSE 8443 8080 8081
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
