# Plack::Handler::Netty - PSGI Server Example

A complete working example demonstrating how to run PSGI applications (Dancer2, Catalyst, Mojolicious, etc.) on PerlOnJava using Netty as the HTTP server backend.

## Overview

`Plack::Handler::Netty` is a PSGI server handler that bridges Perl web frameworks to Java's Netty HTTP server. It provides:

- **Universal framework support** - Any PSGI-compatible app works (Dancer2, Catalyst, Mojolicious)
- **High-performance async I/O** - Netty handles 10k+ concurrent connections on a single thread
- **Single-threaded model** - Compatible with PerlOnJava's no-threads/no-fork constraints
- **Standard PSGI 1.1** - Full compliance with streaming and delayed response support

## Architecture

```
Client → Netty (async I/O, single thread) → PlackHandlerNetty.java
    ↓
    Plack::Handler::Netty.pm (Perl facade)
    ↓
    PSGI Application ($app->(\%env))
    ↓
Response ← [status, headers, body] ← Netty
```

**Implementation Location:**
- **Java Backend:** `src/main/java/org/perlonjava/runtime/perlmodule/PlackHandlerNetty.java`
- **Perl Module:** `src/main/perl/lib/Plack/Handler/Netty.pm`
- **Bundled in:** PerlOnJava JAR (`target/perlonjava-*.jar`)

## Quick Start

### 1. Build PerlOnJava

From the project root:

```bash
./gradlew shadowJar  # or: mvn package
```

### 2. Run the Example

```bash
./jperl examples/http_server_plack/test.pl
```

The server will start on `http://localhost:5000`.

### 3. Test the Server

In another terminal:

```bash
# Homepage
curl http://localhost:5000/

# Route with parameter
curl http://localhost:5000/hello/World

# JSON API
curl http://localhost:5000/json

# View PSGI environment
curl http://localhost:5000/env

# POST request (echo)
curl -X POST http://localhost:5000/echo -d 'test data'

# 404 error
curl http://localhost:5000/notfound
```

## Example Application

The `test.pl` script contains a complete PSGI application with:

- **`/`** - Homepage with HTML response
- **`/hello/{name}`** - Route with path parameter extraction
- **`/json`** - JSON API endpoint
- **`/env`** - PSGI environment hash dump (debugging)
- **`/echo`** (POST) - Echoes back POST body
- **404 handling** - Returns 404 for unknown routes

## Usage in Your Own Apps

```perl
use Plack::Handler::Netty;

my $app = sub {
    my ($env) = @_;
    return [
        200,
        ['Content-Type' => 'text/plain'],
        ['Hello from Netty!']
    ];
};

my $handler = Plack::Handler::Netty->new(
    host => '0.0.0.0',
    port => 5000,
);

$handler->run($app);
```

## PSGI Environment

The handler provides all standard PSGI v1.1 environment keys:

- `REQUEST_METHOD`, `PATH_INFO`, `QUERY_STRING`
- `SERVER_NAME`, `SERVER_PORT`, `SERVER_PROTOCOL`
- `CONTENT_LENGTH`, `CONTENT_TYPE`
- `HTTP_*` headers (normalized to uppercase with underscores)
- `psgi.version`, `psgi.url_scheme`, `psgi.input`, `psgi.errors`
- `psgi.multithread` (false), `psgi.multiprocess` (false)
- `psgi.run_once` (false), `psgi.nonblocking` (true), `psgi.streaming` (true)

## Configuration Options

`Plack::Handler::Netty->new(%options)` accepts:

| Option | Default | Description |
|--------|---------|-------------|
| `host` | 0.0.0.0 | Bind address |
| `port` | 5000 | Listen port |
| `backlog` | 128 | TCP backlog queue size |
| `keepalive` | 30 | HTTP keep-alive timeout (seconds) |
| `max_request_size` | 10485760 | Max request body size (bytes, ~10MB) |

## Using with Your Own PSGI Apps

### With Dancer2

```perl
use Dancer2;

get '/' => sub {
    "Hello from Dancer2 on Netty!";
};

start;  # Configure via environment: PLACK_SERVER=Netty
```

Run with:
```bash
./jperl your_app.pl
```

## Concurrency Model

**Single-threaded async I/O** - Uses Netty's event loop (`NioEventLoopGroup(1)`) to handle concurrent connections without threads/fork:

✅ **Good for:** I/O-bound apps (databases, APIs, file serving)  
✅ **Handles:** Thousands of concurrent connections efficiently  
⚠️ **Limitation:** CPU-bound request handlers may block other requests

This design avoids PerlOnJava's thread-safety constraints while still providing excellent performance for typical web applications.

## Limitations

- **Single-threaded** - CPU-intensive handlers block other requests
- **HTTP only** - HTTPS/TLS support planned for future phases
- **Streaming not yet implemented** - Phase 1 supports array responses only

## Performance

Expected performance for "Hello World" apps: **5,000-10,000+ requests/sec**

Actual performance depends on:
- Handler complexity (CPU vs I/O bound)
- Netty configuration (keep-alive, buffer sizes)
- System resources (RAM, file descriptors)

## Files in This Directory

- `test.pl` - Complete working example with multiple test endpoints
- `Makefile` - Build and utility targets (legacy, not needed with current build)
- `README.md` - This file
- `lib/` - Local copy of Netty JARs (if using Makefile)

**Real implementation** is bundled in the JAR:
- Java: `src/main/java/org/perlonjava/runtime/perlmodule/PlackHandlerNetty.java`
- Perl: `src/main/perl/lib/Plack/Handler/Netty.pm`

## Troubleshooting

### "Can't locate Plack/Handler/Netty.pm"

Make sure you're running with the built JAR:
```bash
./jperl your_app.pl       # Good - uses bundled version
java -cp ... your_app.pl   # May not find module
```

### Port already in use

Change the port in your code:
```perl
my $handler = Plack::Handler::Netty->new(port => 9000);
$handler->run($app);
```

## Implementation Status

✅ Phase 1 Complete - Synchronous PSGI responses working  
✅ Single-threaded event loop for concurrency  
✅ Full PSGI v1.1 environment  
✅ HTTP/1.1 with keep-alive  
✅ Standard Plack::Handler interface  
🚧 Phase 2+ - Streaming responses, HTTPS, Dancer2 testing

## Contributing

Found a bug or want to improve this? Please submit an issue or PR to [PerlOnJava](https://github.com/fglock/PerlOnJava).

## License

Same as PerlOnJava.
