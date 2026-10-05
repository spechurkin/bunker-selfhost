FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B verify

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN groupadd --system bunker && useradd --system --gid bunker bunker
COPY --from=build /workspace/target/bunker-server.jar /app/bunker-server.jar
USER bunker
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/bunker-server.jar"]
