# For instructions on building and running this Docker image,
# please refer to docs/DOCKER.md

# Use Eclipse Temurin JDK 21 as the base image
FROM eclipse-temurin:21-jdk AS build

# Install Maven
RUN apt-get update && \
    apt-get install -y maven && \
    rm -rf /var/lib/apt/lists/*

# Set the working directory inside the container
WORKDIR /app

# Copy the entire project into the container
COPY . .

# Run Maven to build the project
RUN mvn clean package

# Use Eclipse Temurin JDK image to run the application
FROM eclipse-temurin:21-jdk

# Copy the built JAR file from the Maven container
COPY --from=build /app/target/perlonjava-3.0.0.jar /app/perlonjava.jar

# Set the entry point to run the JAR file
ENTRYPOINT ["java", "-jar", "/app/perlonjava.jar"]
