FROM maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
RUN addgroup --system milestone && adduser --system --ingroup milestone milestone
WORKDIR /app
COPY --from=build /workspace/target/milestone-ledger-*.jar app.jar
USER milestone
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
