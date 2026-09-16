FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup --system milestone && adduser --system --ingroup milestone milestone
WORKDIR /app
COPY --from=build /workspace/target/milestone-ledger-*.jar app.jar
USER milestone
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
