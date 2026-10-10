FROM library/maven:3.9.11-eclipse-temurin-17@sha256:e4a7ace3dc0d645ed97f8d9ad0b0d3f0b14fa8d150138f27f116d7105a639b82 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package

FROM library/eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
WORKDIR /app
RUN mkdir -p /app/data /app/logs && chown -R 10001:10001 /app
COPY --from=build /build/target/api-spring-0.0.1-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
