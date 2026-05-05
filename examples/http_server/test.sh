#!/bin/bash
# Test script for PerlOnJava HTTP Server
# This script tests all endpoints and verifies responses

set -e

SERVER_URL="${SERVER_URL:-http://localhost:8080}"
FAILED=0

echo "Testing PerlOnJava HTTP Server at $SERVER_URL"
echo "================================================"
echo ""

# Function to test an endpoint
test_endpoint() {
    local method="$1"
    local path="$2"
    local data="$3"
    local expected="$4"
    local description="$5"

    echo -n "Testing: $description ... "

    if [ -n "$data" ]; then
        response=$(curl -s -X "$method" "$SERVER_URL$path" -d "$data")
    else
        response=$(curl -s -X "$method" "$SERVER_URL$path")
    fi

    if echo "$response" | grep -q "$expected"; then
        echo "✓ PASS"
        return 0
    else
        echo "✗ FAIL"
        echo "  Expected to find: $expected"
        echo "  Got: ${response:0:100}..."
        FAILED=$((FAILED + 1))
        return 1
    fi
}

# Wait for server to be ready
echo "Checking if server is running..."
for i in {1..10}; do
    if curl -s "$SERVER_URL/" > /dev/null 2>&1; then
        echo "Server is ready!"
        echo ""
        break
    fi
    if [ $i -eq 10 ]; then
        echo "Error: Server is not responding at $SERVER_URL"
        echo "Make sure the server is running: make run"
        exit 1
    fi
    sleep 1
done

# Run tests
test_endpoint "GET" "/" "" "PerlOnJava HTTP Server" "Home page"
test_endpoint "GET" "/api/users" "" "Alice" "API - users"
test_endpoint "GET" "/api/products" "" "Widget" "API - products"
test_endpoint "GET" "/api/unknown" "" "Resource not found" "API - 404"
test_endpoint "POST" "/form" "name=Alice&age=30" "Alice" "POST form"
test_endpoint "GET" "/form" "" "Method Not Allowed" "Form - wrong method"
test_endpoint "GET" "/time" "" "Local Time" "Time endpoint"
test_endpoint "GET" "/env" "" "Request Information" "Environment info"
test_endpoint "GET" "/echo?message=Hello&count=2" "" "1: Hello" "Echo service"
test_endpoint "GET" "/notfound" "" "404 - Not Found" "404 handler"

echo ""
echo "================================================"
if [ $FAILED -eq 0 ]; then
    echo "All tests passed! ✓"
    exit 0
else
    echo "$FAILED test(s) failed ✗"
    exit 1
fi
