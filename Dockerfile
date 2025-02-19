FROM openjdk:21-jdk
COPY target/perlonjava-1.0-SNAPSHOT.jar /app/perlonjava.jar
ENTRYPOINT ["java", "-jar", "/app/perlonjava.jar"]

