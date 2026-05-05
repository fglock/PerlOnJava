# Makefile for PerlOnJava HTTP Server Example
#
# This example demonstrates a web server using Netty and PerlOnJava where
# HTTP request handlers are written in Perl.

# Configuration
JAVA_VERSION = 22
NETTY_VERSION = 4.1.115.Final
PERLONJAVA_JAR = target/perlonjava-5.42.0.jar
PERLONJAVA_JAR_LOCAL = ../../target/perlonjava-5.42.0.jar
LIB_DIR = lib
NETTY_JARS = \
	$(LIB_DIR)/netty-common-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-buffer-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-transport-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-codec-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-codec-http-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-handler-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-resolver-$(NETTY_VERSION).jar \
	$(LIB_DIR)/netty-transport-classes-epoll-$(NETTY_VERSION).jar

# Java compiler and runtime flags
JAVAC = javac
JAVA = java
JAVAC_FLAGS = -cp "$(PERLONJAVA_JAR_LOCAL):$(LIB_DIR)/*"
JAVA_FLAGS = --enable-native-access=ALL-UNNAMED -cp "$(PERLONJAVA_JAR):examples/http_server/$(LIB_DIR)/*:."

# Targets
.PHONY: all clean deps compile run test help

# Default target
all: deps compile

# Download Netty dependencies
deps:
	@echo "Checking dependencies..."
	@mkdir -p $(LIB_DIR)
	@for jar in netty-common netty-buffer netty-transport netty-codec netty-codec-http netty-handler netty-resolver netty-transport-classes-epoll; do \
		if [ ! -f "$(LIB_DIR)/$$jar-$(NETTY_VERSION).jar" ]; then \
			echo "Downloading $$jar-$(NETTY_VERSION).jar..."; \
			curl -f -L -o "$(LIB_DIR)/$$jar-$(NETTY_VERSION).jar" \
				"https://repo1.maven.org/maven2/io/netty/$$jar/$(NETTY_VERSION)/$$jar-$(NETTY_VERSION).jar" || \
				echo "Warning: Failed to download $$jar (may not be needed)"; \
		fi; \
	done
	@echo "Dependencies ready."

# Ensure PerlOnJava JAR exists
check-jar:
	@if [ ! -f "$(PERLONJAVA_JAR_LOCAL)" ]; then \
		echo "Error: PerlOnJava JAR not found at $(PERLONJAVA_JAR_LOCAL)"; \
		echo "Please build it first:"; \
		echo "  cd ../.. && make"; \
		echo "  or: ./gradlew shadowJar"; \
		exit 1; \
	fi

# Compile the Java source
compile: check-jar deps
	@echo "Compiling HttpServerExample.java..."
	$(JAVAC) $(JAVAC_FLAGS) HttpServerExample.java

# Run the HTTP server
run: compile
	@echo "Starting HTTP server on port 8080..."
	@echo ""
	cd ../.. && $(JAVA) $(JAVA_FLAGS) examples.http_server.HttpServerExample

# Run on a custom port
run-port: compile
	@echo "Starting HTTP server on port $(PORT)..."
	@echo ""
	cd ../.. && $(JAVA) $(JAVA_FLAGS) examples.http_server.HttpServerExample $(PORT)

# Test the server with curl
test:
	@echo "Testing HTTP server endpoints..."
	@echo ""
	@echo "=== GET / (Home) ==="
	@curl -s http://localhost:8080/ | head -20
	@echo ""
	@echo ""
	@echo "=== GET /api/users (API) ==="
	@curl -s http://localhost:8080/api/users
	@echo ""
	@echo ""
	@echo "=== GET /time ==="
	@curl -s http://localhost:8080/time | grep -A2 "Local Time"
	@echo ""
	@echo ""
	@echo "=== POST /form ==="
	@curl -s -X POST http://localhost:8080/form -d "name=Alice&age=30" | grep -A5 "Form Received"
	@echo ""
	@echo ""
	@echo "=== GET /echo ==="
	@curl -s "http://localhost:8080/echo?message=Hello&count=2" | grep -A3 "Echo Service"
	@echo ""
	@echo ""
	@echo "Done!"

# Clean compiled files
clean:
	@echo "Cleaning up..."
	rm -f HttpServerExample.class
	rm -f HttpServerExample$$HttpRequestHandler.class
	rm -rf $(LIB_DIR)

# Show help
help:
	@echo "PerlOnJava HTTP Server Example - Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  make          - Download dependencies and compile"
	@echo "  make deps     - Download Netty JAR"
	@echo "  make compile  - Compile Java source"
	@echo "  make run      - Run the HTTP server (port 8080)"
	@echo "  make run-port PORT=9000 - Run on custom port"
	@echo "  make test     - Test server endpoints with curl"
	@echo "  make clean    - Remove compiled files and dependencies"
	@echo "  make help     - Show this help message"
	@echo ""
	@echo "Usage:"
	@echo "  1. Build PerlOnJava first: cd ../.. && make"
	@echo "  2. Run server: cd examples/http_server && make run"
	@echo "  3. Test: curl http://localhost:8080/"
	@echo "     or: make test (in another terminal)"
