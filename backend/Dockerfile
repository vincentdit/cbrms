# Multi-stage build: Maven builds the jar, a JRE image runs it as a non-root user.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 cbrms
WORKDIR /app
COPY --from=build /src/target/cbrms-*.jar /app/cbrms.jar
USER cbrms
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/cbrms.jar"]
