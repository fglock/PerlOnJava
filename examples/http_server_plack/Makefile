.PHONY: help run https streaming certs test clean benchmark

# Default target
help:
	@echo "PerlOnJava PSGI Web Server Examples"
	@echo ""
	@echo "Targets:"
	@echo "  make run        - Start HTTP server on port 5000"
	@echo "  make https      - Start HTTPS server on port 8443 (generates certs if needed)"
	@echo "  make streaming  - Start streaming response test server"
	@echo "  make certs      - Generate test SSL certificates"
	@echo "  make test       - Test all endpoints"
	@echo "  make benchmark  - Run performance benchmarks (requires 'ab')"
	@echo "  make clean      - Remove generated certificates"
	@echo ""
	@echo "Examples:"
	@echo "  make run        # Start server, then: curl http://localhost:5000/"
	@echo "  make https      # Start HTTPS, then: curl -k https://localhost:8443/"

# Start HTTP server
run:
	@echo "Starting HTTP server on http://localhost:5000"
	@echo "Press Ctrl+C to stop"
	@echo ""
	../../jperl test.pl

# Start HTTPS server (generate certs if needed)
https: certs
	@echo "Starting HTTPS server on https://localhost:8443"
	@echo "Press Ctrl+C to stop"
	@echo ""
	../../jperl test_https.pl

# Start streaming test server
streaming:
	@echo "Starting streaming response test server on http://localhost:5000"
	@echo "Press Ctrl+C to stop"
	@echo ""
	../../jperl test_streaming.pl

# Generate test certificates
certs:
	@if [ ! -f certs/server-cert.pem ]; then \
		echo "Generating test SSL certificates..."; \
		cd certs && ./generate_test_cert.sh; \
	else \
		echo "SSL certificates already exist (certs/server-cert.pem)"; \
	fi

# Test endpoints
test:
	@echo "Testing HTTP endpoints..."
	@echo ""
	@echo "Starting server in background..."
	@../../jperl test.pl > /tmp/plack_test.log 2>&1 & \
	SERVER_PID=$$!; \
	sleep 2; \
	echo "Testing endpoints:"; \
	echo "  GET /"; \
	curl -s http://localhost:5000/ | head -3; \
	echo "  GET /json"; \
	curl -s http://localhost:5000/json; \
	echo "  GET /hello/World"; \
	curl -s http://localhost:5000/hello/World; \
	echo ""; \
	echo "Stopping server..."; \
	kill $$SERVER_PID 2>/dev/null || true; \
	wait $$SERVER_PID 2>/dev/null || true; \
	echo "✓ Tests complete"

# Run performance benchmarks (requires Apache Bench)
benchmark:
	@if ! command -v ab >/dev/null 2>&1; then \
		echo "Error: Apache Bench (ab) not found"; \
		echo "Install with: brew install httpd (macOS) or apt-get install apache2-utils (Linux)"; \
		exit 1; \
	fi
	@echo "Starting server in background..."
	@../../jperl test.pl > /tmp/plack_bench.log 2>&1 & \
	SERVER_PID=$$!; \
	sleep 2; \
	echo "Running benchmarks..."; \
	echo ""; \
	echo "Test 1: Hello World (10,000 requests, concurrency 100)"; \
	ab -n 10000 -c 100 -q http://localhost:5000/ 2>&1 | grep -E "Requests per second|Time per request|Transfer rate"; \
	echo ""; \
	echo "Test 2: JSON API (5,000 requests, concurrency 50)"; \
	ab -n 5000 -c 50 -q http://localhost:5000/json 2>&1 | grep -E "Requests per second|Time per request|Transfer rate"; \
	echo ""; \
	kill $$SERVER_PID 2>/dev/null || true; \
	wait $$SERVER_PID 2>/dev/null || true; \
	echo "✓ Benchmarks complete"

# Clean generated files
clean:
	@echo "Removing generated test certificates..."
	@rm -f certs/server-cert.pem certs/server-key.pem
	@echo "✓ Clean complete"
