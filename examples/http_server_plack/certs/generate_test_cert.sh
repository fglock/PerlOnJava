#!/bin/bash
# Generate self-signed test certificates for HTTPS testing
# WARNING: These are for testing only. Do NOT use in production.

cd "$(dirname "$0")"

echo "Generating self-signed test certificates..."
echo ""

openssl req -x509 -newkey rsa:2048 -keyout server-key.pem \
    -out server-cert.pem -days 365 -nodes \
    -subj "/C=US/ST=Test/L=Test/O=PerlOnJava/CN=localhost"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Generated test certificates:"
    echo "  server-cert.pem - Certificate"
    echo "  server-key.pem  - Private key"
    echo ""
    echo "WARNING: These are self-signed test certificates."
    echo "Do NOT use in production. Get proper certificates from Let's Encrypt."
    echo ""
    echo "Test with:"
    echo "  curl -k https://localhost:8443/"
else
    echo ""
    echo "✗ Certificate generation failed"
    exit 1
fi
