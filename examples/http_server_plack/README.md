# Plack::Handler::Netty - PSGI Server Example

A complete example demonstrating how to run PSGI applications (Dancer2, Catalyst, Mojolicious, etc.) on PerlOnJava using Netty as the HTTP server backend.

## Overview

This example implements `Plack::Handler::Netty`, a PSGI server handler that bridges Perl web frameworks to Java's Netty HTTP server, enabling:

- **Universal framework support** - Any PSGI-compatible app works (Dancer2, Catalyst, Mojolicious)
- **High-performance async I/O** - Netty handles 10k+ concurrent connections
- **Single-threaded model** - Compatible with PerlOnJava's no-threads/no-fork constraints
- **Standard PSGI** - Full PSGI 1.1 compliance with streaming support

## Architecture

```
Browser/Client → Netty (async I/O) → NettyPSGIServer.java
    → Plack::Handler::Netty.pm → PSGI App (Dancer2/etc)
    → Response → Netty → Browser/Client
```

**Key Components:**
- `NettyPSGIServer.java` - Java backend that wraps Netty HTTP server
- `Netty.pm` - Perl module implementing `Plack::Handler` interface
- Test apps in `../../dev/sandbox/http_server/`

## Quick Start

### 1. Build PerlOnJava

```bash
cd ../..  # to project root
make
```

### 2. Download Dependencies and Compile

```bash
cd examples/http_server_plack
make
```

This downloads Netty JARs (~5MB) and compiles `NettyPSGIServer.java`.

### 3. Run Test Applications

**Minimal PSGI app** (no framework):
```bash
make test-minimal
```

Then test with:
```bash
curl http://localhost:5000/
curl http://localhost:5000/hello/World
curl http://localhost:5000/json
curl -X POST http://localhost:5000/echo -d 'test data'
```

**Dancer2 app** (requires Dancer2 installed):
```bash
# First install Dancer2
cd ../..
./jcpan -t Dancer2
cd examples/http_server_plack

# Run test
make test-dancer
```

Then test with:
```bash
curl http://localhost:5000/
curl http://localhost:5000/user/123
curl http://localhost:5000/api/users
```

## Files

| File | Purpose |
|------|---------|
| `NettyPSGIServer.java` | Java PSGI server backend (wraps Netty) |
| `Netty.pm` | Perl module (`Plack::Handler::Netty`) |
| `Makefile` | Build and run targets |
| `README.md` | This file |

Test applications are in `../../dev/sandbox/http_server/`:
- `test_netty_handler.pl` - Minimal PSGI app
- `test_dancer.pl` - Dancer2 integration test
- `dancer_app.pl` - Sample Dancer2 application

## How It Works

### PSGI Environment Construction

`NettyPSGIServer.java` converts Netty's `HttpRequest` to a standard PSGI environment hash with all required keys:

- CGI variables: `REQUEST_METHOD`, `PATH_INFO`, `QUERY_STRING`, etc.
- HTTP headers: `HTTP_USER_AGENT`, `HTTP_ACCEPT`, etc.
- PSGI keys: `psgi.version`, `psgi.input`, `psgi.errors`, `psgi.url_scheme`, etc.

### Response Conversion

Converts PSGI response format `[status, headers, body]` to Netty `FullHttpResponse`.

### Concurrency Model

**Single-threaded async I/O** - Uses Netty's event loop (`NioEventLoopGroup(1)`) to handle concurrent connections without threads/fork:

✅ **Good for:** I/O-bound apps (databases, APIs, file serving)  
✅ **Handles:** Thousands of concurrent connections efficiently  
⚠️ **Limitation:** CPU-bound handlers may block other requests

This design avoids PerlOnJava's thread-safety constraints while still providing excellent performance for typical web applications.

## Using with Your Own PSGI Apps

### Standalone Script

```perl
#!/usr/bin/env perl
use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/examples/http_server_plack";
use Plack::Handler::Netty;

my $app = sub {
    my ($env) = @_;
    return [
        200,
        ['Content-Type' => 'text/plain'],
        ["Hello from PSGI!"]
    ];
};

my $handler = Plack::Handler::Netty->new(port => 5000);
$handler->run($app);
```

Run with:
```bash
java --enable-native-access=ALL-UNNAMED \
  -cp "target/perlonjava-5.42.0.jar:examples/http_server_plack/lib/*:examples/http_server_plack" \
  org.perlonjava.app.cli.Main your_app.pl
```

### With Dancer2

```perl
use Dancer2;

get '/' => sub {
    "Hello from Dancer2 on Netty!";
};

# Get PSGI app and run with Netty
to_app;
```

See `../../dev/sandbox/http_server/dancer_app.pl` for a complete example.

## Configuration Options

`Plack::Handler::Netty->new(%options)` accepts:

| Option | Default | Description |
|--------|---------|-------------|
| `host` | 0.0.0.0 | Bind address |
| `port` | 5000 | Listen port |
| `backlog` | 128 | TCP backlog queue size |
| `keepalive` | 30 | HTTP keep-alive timeout (seconds) |
| `max_request_size` | 10485760 | Max request body size (bytes) |

## PSGI Compliance

Implements **PSGI 1.1** specification with:

✅ Standard array responses: `[status, headers, body]`  
✅ All required environment keys  
🚧 Streaming responses (Phase 3 - coming soon)  
🚧 Delayed responses (Phase 3 - coming soon)

## Limitations

- **Single-threaded** - CPU-intensive handlers block other requests
- **No fork/threads** - PerlOnJava limitation
- **Streaming not yet implemented** - Phase 1 supports array responses only
- **HTTP only** - HTTPS/TLS support planned for later phases

## Comparison with Original Prototype

This PSGI implementation differs from `examples/http_server/`:

| Aspect | Original Prototype | This (PSGI) |
|--------|-------------------|-------------|
| Interface | Custom hash format | Standard PSGI |
| Frameworks | None (raw Perl) | Any PSGI framework |
| Response format | `{status, content_type, body}` | `[status, headers, body]` |
| Middleware | Not possible | All Plack middleware works |
| Reusability | One-off example | Production-ready handler |

## Related Documents

- `../../dev/modules/plack_handler_netty.md` - Implementation plan
- `../../dev/sandbox/http_server/README.md` - Test applications
- `../http_server/README.md` - Original prototype

## Troubleshooting

### "Failed to load NettyPSGIServer Java class"

Make sure you compiled first:
```bash
make compile
```

### "Can't locate Plack/Handler/Netty.pm"

Add the examples directory to Perl's search path:
```bash
java ... -cp "...:examples/http_server_plack" org.perlonjava.app.cli.Main -I./examples/http_server_plack your_app.pl
```

### Dancer2 not found

Install Dancer2:
```bash
./jcpan -t Dancer2
```

### Port already in use

Change the port:
```perl
my $handler = Plack::Handler::Netty->new(port => 9000);
```

## Performance

Expected performance for "Hello World" apps: **5,000-10,000 requests/sec**

Actual performance depends on:
- Handler complexity (CPU vs I/O bound)
- Netty configuration (keep-alive, buffer sizes)
- System resources (RAM, file descriptors)

## Next Steps

This is **Phase 1** (synchronous responses). Future phases:

- **Phase 2** - Full Dancer2 integration testing
- **Phase 3** - Streaming and delayed responses
- **Phase 4** - Production features (SSL/TLS, graceful shutdown)
- **Phase 5** - Bundle in PerlOnJava (add Netty to build.gradle)

## Contributing

Found a bug or want to improve this example? Please submit an issue or PR to the [PerlOnJava repository](https://github.com/fglock/PerlOnJava).

## License

Same as PerlOnJava.
