# The token server as a container (docker-compose.vpn.yml). Build first:   mvn -f token-server/pom.xml -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/token-server-0.0.1-SNAPSHOT.jar app.jar
ENV TLS_DIR=file:/certs
EXPOSE 9443
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
