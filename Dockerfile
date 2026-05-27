FROM eclipse-temurin:17-jre

WORKDIR /app

ENV PAYLOADPROBE_STORE_PATH=/data/xmlResponses.json

COPY target/payloadprobe-lab-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
