# PerlOnJava HTTP Server Example

A complete HTTP web server example using **Netty** and **PerlOnJava**, where request handlers are written in Perl for easy customization.

## Overview

This example demonstrates how to:

- Create a high-performance web server using Netty's async I/O
- Write request handlers in Perl (loaded from external files)
- Handle thread safety in PerlOnJava (currently single-threaded event loop)
- Route HTTP requests and return HTML/JSON responses
- Bridge between Java (Netty) and Perl (request handlers)

## Architecture

```
┌─────────────┐
│   Browser   │
└──────┬──────┘
       │ HTTP Request
       ▼
┌──────────────────────────────┐
│  Netty HTTP Server (Java)    │
│  - Single event loop thread  │
│  - Handles concurrent I/O    │
└──────┬───────────────────────┘
       │ Call handle_request()
       ▼
┌──────────────────────────────┐
│  Perl Handler (handler.pl)   │
│  - Routes requests           │
│  - Generates responses       │
└──────┬───────────────────────┘
       │ Return response hash
       ▼
┌──────────────────────────────┐
│  Netty sends HTTP response   │
└──────────────────────────────┘
```

## Thread Safety

**IMPORTANT:** PerlOnJava is currently **NOT thread-safe**. All global Perl state (variables, arrays, hashes) is stored in static fields without synchronization.

This example uses a **single-threaded event loop** to avoid race conditions:

- ✅ Only one thread accesses Perl state
- ✅ Multiple concurrent connections supported (via async I/O)
- ✅ No locks needed
- ❌ CPU-bound handlers can block other requests

**Alternative approach** (not used here):
- Use a global lock: `synchronized(PERL_LOCK) { ... }`
- This serializes all requests but works with multi-threaded event loops

**Future:** Once multiplicity is implemented (see `dev/design/concurrency.md`), you can use a pool of isolated Perl runtimes for true parallel request handling.

## Prerequisites

1. **Java 22+** - Required by PerlOnJava
2. **Build PerlOnJava** - The fat JAR must be built first
3. **curl** - For testing (optional)

## Quick Start

### 1. Build PerlOnJava

```bash
# From the project root
make
# or: ./gradlew shadowJar
```

This creates `target/perlonjava-5.42.0.jar`.

### 2. Build and Run the Server

```bash
cd examples/http_server
make run
```

This will:
- Download Netty JAR (first time only)
- Compile `HttpServerExample.java`
- Start the server on `http://localhost:8080`

### 3. Test the Server

Open your browser and visit:
- http://localhost:8080/ - Home page with endpoint list
- http://localhost:8080/api/users - JSON API example
- http://localhost:8080/time - Current server time
- http://localhost:8080/env - Request environment info
- http://localhost:8080/echo?message=Hello&count=3 - Echo service

Or use curl:
```bash
# Home page
curl http://localhost:8080/

# API endpoint
curl http://localhost:8080/api/users

# POST form data
curl -X POST http://localhost:8080/form -d "name=Alice&age=30"

# Echo with query params
curl "http://localhost:8080/echo?message=Hello%20World&count=3"
```

Or run the automated tests:
```bash
# In another terminal
make test
```

## File Structure

```
examples/http_server/
├── HttpServerExample.java  # Java/Netty server code
├── handler.pl              # Perl request handler
├── Makefile               # Build and run commands
└── README.md              # This file
```

## Customizing the Perl Handler

The Perl handler in `handler.pl` receives a request hash:

```perl
sub handle_request {
    my ($request) = @_;

    # Request structure:
    # {
    #   method   => 'GET',
    #   path     => '/api/users',
    #   uri      => '/api/users?id=123',
    #   body     => 'request body',
    #   query    => { id => '123' },
    #   headers  => { 'content-type' => 'application/json' }
    # }

    # Return a response hash:
    return {
        status       => 200,
        content_type => 'text/html',
        body         => '<h1>Hello from Perl!</h1>'
    };
}
```

### Adding New Routes

Edit `handler.pl` and add your route:

```perl
sub handle_request {
    my ($request) = @_;
    my $path = $request->{path};

    if ($path eq '/my-new-page') {
        return {
            status => 200,
            content_type => 'text/html',
            body => '<h1>My New Page</h1>'
        };
    }
    # ... existing routes
}
```

The server will automatically reload when you restart it (no Java recompilation needed).

## Makefile Targets

```bash
make          # Download dependencies and compile
make deps     # Download Netty JAR only
make compile  # Compile Java source only
make run      # Run server on port 8080
make run-port PORT=9000  # Run on custom port
make test     # Test endpoints with curl
make clean    # Remove compiled files and dependencies
make help     # Show help message
```

## Running on a Different Port

```bash
make run-port PORT=9000
```

Or directly:
```bash
java --enable-native-access=ALL-UNNAMED \
     -cp "../../target/perlonjava-5.42.0.jar:lib/*:." \
     examples.http_server.HttpServerExample 9000
```

## How It Works

1. **Initialization**
   - PerlOnJava runtime is initialized
   - `handler.pl` is loaded and compiled
   - Reference to `handle_request()` subroutine is obtained

2. **Request Handling**
   - Netty receives HTTP request
   - Request data is converted to Perl hash structure
   - `handle_request(\%request)` is called
   - Perl handler returns response hash
   - Netty sends HTTP response to client

3. **Thread Safety**
   - Single event loop thread handles all requests
   - Netty uses async I/O for concurrent connections
   - No Perl state shared between "requests" (but all requests use same runtime)

## Performance Considerations

### Current Implementation (Single Thread)

- ✅ **Good for:** I/O-bound apps (database queries, API calls, file reading)
- ✅ **Handles:** Thousands of concurrent connections efficiently
- ❌ **Bad for:** CPU-intensive tasks (long computations, image processing)

### When Multiplicity is Available

Once PerlOnJava implements runtime isolation (see `dev/design/concurrency.md`), you'll be able to use a **runtime pool**:

```java
PerlRuntimePool pool = new PerlRuntimePool(10);  // 10 isolated runtimes

// In your handler:
PerlRuntime rt = pool.acquire();
try {
    PerlRuntime.setCurrent(rt);
    result = RuntimeCode.apply(handler, args, RuntimeContextType.SCALAR);
} finally {
    pool.release(rt);
}
```

This enables true parallel request processing across CPU cores.

## Troubleshooting

### Error: PerlOnJava JAR not found

```bash
cd ../..
make
# or: ./gradlew shadowJar
```

### Error: Port already in use

```bash
# Use a different port
make run-port PORT=9090
```

Or kill the process using port 8080:
```bash
# macOS/Linux
lsof -ti:8080 | xargs kill -9
```

### Error: Java version

Ensure you have Java 22+:
```bash
java -version
```

### Connection refused when testing

Make sure the server is running:
```bash
# Terminal 1: Start server
make run

# Terminal 2: Test
make test
```

## Example Output

```
$ make run
Starting HTTP server on port 8080...

=======================================================
  HTTP Server started on http://localhost:8080
  Thread model: Single event loop (thread-safe)
  Press Ctrl+C to stop
=======================================================
```

## Debugging Tips

### Using --disassemble to Understand Internal APIs

You can see how PerlOnJava handles Perl constructs internally by using the `--disassemble` flag:

```bash
# See how hash references are created
./jperl --disassemble -e 'my $hash = { foo => "bar" }; print $hash->{foo};' 2>&1 | grep -A5 createHashRef
```

This shows that PerlOnJava uses `RuntimeHash.createHashRef()` to create hash references, which is why we use:

```java
RuntimeScalar hashRef = RuntimeHash.createHashRef(request);
```

instead of manually constructing the scalar. This approach ensures compatibility with PerlOnJava's internal representation.

## Related Examples

- `examples/ExifToolExample.java` - Calling Perl modules from Java
- `examples/http.pl` - HTTP client using HTTP::Tiny

## Further Reading

- [Netty Documentation](https://netty.io/wiki/)
- [PerlOnJava Concurrency Design](../../dev/design/concurrency.md)
- [PerlOnJava Features](../../docs/reference/feature-matrix.md)

## License

This example is part of the PerlOnJava project and follows the same license.

## Contributing

Found a bug or want to improve this example? Please submit an issue or PR to the [PerlOnJava repository](https://github.com/fglock/PerlOnJava).
