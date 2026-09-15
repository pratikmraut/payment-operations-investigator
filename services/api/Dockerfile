FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp package

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /app/runtime && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/target/payment-operations-api-0.1.0.jar /app/api.jar
USER 10001:10001
EXPOSE 8088
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","/app/api.jar"]
