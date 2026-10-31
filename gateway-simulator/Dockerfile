# The gateway simulator as a container (docker-compose.vpn.yml). Build first:   mvn -f gateway-simulator/pom.xml -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/gateway-simulator-0.0.1-SNAPSHOT.jar app.jar
ENV TLS_DIR=file:/certs
USER 10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
