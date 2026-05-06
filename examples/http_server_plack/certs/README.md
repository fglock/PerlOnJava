# Test Certificates

This directory contains self-signed SSL/TLS certificates for testing HTTPS support in Plack::Handler::Netty.

## Generating Certificates

Run the generation script:

```bash
./generate_test_cert.sh
```

This creates:
- `server-cert.pem` - SSL certificate
- `server-key.pem` - Private key

## ⚠️ WARNING

**These are self-signed test certificates. Do NOT use in production.**

For production deployments, obtain proper certificates from:
- **Let's Encrypt** (free, automated): https://letsencrypt.org/
- **Commercial CA** (paid, with support)

## Testing with Self-Signed Certificates

When testing with curl, use the `-k` flag to skip certificate verification:

```bash
curl -k https://localhost:8443/
```

For browsers, you'll see a security warning. Click "Advanced" and proceed anyway for testing.

## Certificate Details

- **Type**: Self-signed X.509
- **Key size**: 2048-bit RSA
- **Validity**: 365 days
- **Subject**: CN=localhost
- **Format**: PEM

## Production Certificates

For production, use Let's Encrypt with certbot:

```bash
# Install certbot
sudo apt-get install certbot  # Debian/Ubuntu
brew install certbot          # macOS

# Generate certificate
sudo certbot certonly --standalone -d yourdomain.com

# Certificates will be in:
# /etc/letsencrypt/live/yourdomain.com/fullchain.pem  # cert
# /etc/letsencrypt/live/yourdomain.com/privkey.pem    # key
```

Then configure your handler:

```perl
my $handler = Plack::Handler::Netty->new(
    port     => 443,
    ssl      => 1,
    ssl_cert => '/etc/letsencrypt/live/yourdomain.com/fullchain.pem',
    ssl_key  => '/etc/letsencrypt/live/yourdomain.com/privkey.pem',
);
```
