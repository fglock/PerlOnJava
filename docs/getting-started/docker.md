# Docker Guide

Run PerlOnJava in a Docker container for isolated and portable execution.

## Prerequisites

- [Docker](https://www.docker.com/products/docker-desktop) installed on your machine

## Quick Start

### Build the Docker Image

```bash
docker build -t perlonjava:latest .
```

This creates a Docker image named `perlonjava` with the `latest` tag.

### Run Perl Code

```bash
# Simple one-liner
docker run --rm perlonjava:latest -E 'say "Hello from Docker!"'

# Run a script file (mount current directory)
docker run --rm -v "$(pwd)":/scripts perlonjava:latest /scripts/myscript.pl

# Interactive shell
docker run --rm -it perlonjava:latest -de0
```

### Common Options

**`--rm`**: Automatically remove the container when it exits

**`-v /path/to/scripts:/scripts`**: Mount local directory for script access

**`-e VAR=value`**: Set environment variables

**`--name myname`**: Assign a name to the container for easier management

## Examples

### Running Tests

```bash
# Run unit tests in Docker
docker run --rm perlonjava:latest -MTAP::Harness -e 'TAP::Harness->new->runtests("/app/src/test/resources/unit/array.t")'
```

### With JDBC Drivers

To use JDBC drivers, add them to the Dockerfile or mount them at runtime:

```bash
# Mount driver and use it
docker run --rm \
  -v /path/to/mysql-connector.jar:/drivers/mysql.jar \
  -e CLASSPATH=/drivers/mysql.jar \
  perlonjava:latest your_script.pl
```

### Persistent Containers

If you need to keep a container running:

```bash
# Start named container
docker run --name perlonjava_app -d perlonjava:latest -E 'sleep infinity'

# Execute commands in running container
docker exec perlonjava_app jperl -E 'say "Hello"'

# Stop and remove
docker stop perlonjava_app
docker rm perlonjava_app
```

## Customizing the Dockerfile

Modify the `Dockerfile` to include additional dependencies:

```dockerfile
# Add JDBC driver
FROM eclipse-temurin:21-jdk
COPY --from=build /app/target/perlonjava-3.0.0.jar /app/perlonjava.jar
COPY path/to/driver.jar /app/drivers/
ENV CLASSPATH=/app/drivers/driver.jar
ENTRYPOINT ["java", "-jar", "/app/perlonjava.jar"]
```

## See Also

- [Installation Guide](installation.md) - Build without Docker
- [Database Access](../guides/database-access.md) - Using JDBC drivers


