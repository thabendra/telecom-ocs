# The token server as a container (docker-compose.vpn.yml). Build first:   mvn -f token-server/pom.xml -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/token-server-0.0.1-SNAPSHOT.jar app.jar
ENV TLS_DIR=file:/certs
ENV SIGNING_KEY_DIR=/keys
# SE-7: the signing keys: a named volume on this path keeps them, and takes over this owner
RUN mkdir /keys && chown 10001 /keys
EXPOSE 9443
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
