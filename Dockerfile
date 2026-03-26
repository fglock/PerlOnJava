# For instructions on building and running this Docker image,
# please refer to docs/DOCKER.md

# Use Eclipse Temurin JDK 22 as the base image
FROM eclipse-temurin:22-jdk AS build

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
FROM eclipse-temurin:22-jdk

WORKDIR /app

# Copy the built JAR file from the Maven container
COPY --from=build /app/target/perlonjava-5.42.0.jar /app/perlonjava-5.42.0.jar

# Copy the wrapper scripts
COPY --from=build /app/jperl /app/jperl
COPY --from=build /app/jcpan /app/jcpan
COPY --from=build /app/jperldoc /app/jperldoc
COPY --from=build /app/jprove /app/jprove

# Copy the Perl bin scripts (needed by jcpan, jperldoc, jprove)
COPY --from=build /app/src/main/perl/bin /app/bin

# Make scripts executable and create symlinks in /usr/local/bin
RUN chmod +x /app/jperl /app/jcpan /app/jperldoc /app/jprove && \
    ln -s /app/jperl /usr/local/bin/jperl && \
    ln -s /app/jcpan /usr/local/bin/jcpan && \
    ln -s /app/jperldoc /usr/local/bin/jperldoc && \
    ln -s /app/jprove /usr/local/bin/jprove

# Set the entry point to run jperl by default
ENTRYPOINT ["jperl"]
