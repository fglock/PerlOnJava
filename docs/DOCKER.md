## Running the Application with Docker

This project includes a `Dockerfile` that allows you to build and run the application in a Docker container. Follow the steps below to get started:

### Prerequisites

- Ensure you have [Docker](https://www.docker.com/products/docker-desktop) installed on your machine.

### Building the Docker Image

To build the Docker image, navigate to the root directory of the project where the `Dockerfile` is located and run the following command:

```bash
docker build -t perlonjava:latest .
```

This command will create a Docker image named `perlonjava` with the `latest` tag.

### Running the Docker Container

Once the image is built, you can run the application in a Docker container using the following command:

```bash
docker run --name perlonjava_app -p 8080:8080 perlonjava:latest -E ' say "hello, World!" '
```

- `--name perlonjava_app`: Assigns a name to the running container.
- `-p 8080:8080`: Maps port 8080 of the host to port 8080 of the container. Adjust the ports as needed for your application.

### Stopping and Removing the Container

To stop the running container, use the following command:

```bash
docker stop perlonjava_app
```

To remove the container after stopping it, run:

```bash
docker rm perlonjava_app
```

### Additional Notes

- Ensure that any required environment variables or configuration files are properly set up before running the container.
- You can modify the `Dockerfile` to include additional dependencies or configurations as needed for your specific use case.


