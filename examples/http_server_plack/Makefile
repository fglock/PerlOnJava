# Makefile for Plack::Handler::Netty - PSGI Server Example
#
# This example demonstrates a PSGI server using Netty and PerlOnJava
# that can run any PSGI application (Dancer2, Catalyst, Mojolicious, etc.)

# Configuration
JAVA_VERSION = 22
NETTY_VERSION = 4.1.115.Final
PERLONJAVA_JAR = ../../target/perlonjava-5.42.0.jar
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
JAVAC_FLAGS = -cp "$(PERLONJAVA_JAR):$(LIB_DIR)/*"
JAVA_FLAGS = --enable-native-access=ALL-UNNAMED -cp "$(PERLONJAVA_JAR):$(LIB_DIR)/*:."
JPERL = $(JAVA) $(JAVA_FLAGS) org.perlonjava.app.cli.Main

# Targets
.PHONY: all clean deps compile test-minimal test-dancer help

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

# Compile Java class
compile: deps
	@echo "Compiling NettyPSGIServer.java..."
	@if [ ! -f "$(PERLONJAVA_JAR)" ]; then \
		echo "Error: PerlOnJava JAR not found. Run 'make' in project root first."; \
		exit 1; \
	fi
	$(JAVAC) $(JAVAC_FLAGS) NettyPSGIServer.java
	@echo "Compilation successful."

# Run minimal PSGI test app
test-minimal: compile
	@echo "Starting minimal PSGI test server..."
	$(JPERL) -I. ../../dev/sandbox/http_server/test_netty_handler.pl

# Run Dancer2 test app (requires Dancer2 installed)
test-dancer: compile
	@echo "Starting Dancer2 test server..."
	$(JPERL) -I. ../../dev/sandbox/http_server/test_dancer.pl

# Clean compiled files and dependencies
clean:
	rm -f *.class
	rm -rf $(LIB_DIR)
	@echo "Clean complete."

# Help message
help:
	@echo "Plack::Handler::Netty - PSGI Server Example"
	@echo ""
	@echo "Available targets:"
	@echo "  make               - Download dependencies and compile"
	@echo "  make deps          - Download Netty JARs"
	@echo "  make compile       - Compile Java class"
	@echo "  make test-minimal  - Run minimal PSGI app (no framework)"
	@echo "  make test-dancer   - Run Dancer2 test app"
	@echo "  make clean         - Remove compiled files and dependencies"
	@echo "  make help          - Show this help message"
	@echo ""
	@echo "Requirements:"
	@echo "  - Java $(JAVA_VERSION)+"
	@echo "  - PerlOnJava built (run 'make' in project root)"
	@echo "  - Dancer2 installed for test-dancer (run './jcpan -t Dancer2')"
