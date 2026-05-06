# Plack::Handler::Netty - PSGI Server Backend for PerlOnJava

## Status: Phase 1 - Complete ✅, Phase 2 - Blocker Fixed 🚧

- **Module version**: Plack::Handler::Netty 0.01
- **Date started**: 2026-05-06
- **PR merged**: #662 (2026-05-06)
- **Test location**: `examples/http_server_plack/test.pl`
- **Build system**: Maven (pom.xml) + Gradle (build.gradle)

## Recent Work

**2026-05-06 - Fixed MIME::Base64.encode_base64url blocker**
- Added URL-safe base64 encoding functions (RFC 4648)
- Implemented `encode_base64url()` and `decode_base64url()` in Java backend
- Dancer2 can now be imported without import errors
- Session factories and components load successfully

## Progress Tracking

### Phase 1: Core PSGI Handler ✅ COMPLETE

**Goal**: Implement basic Plack::Handler::Netty with synchronous response support.

- [x] Java backend (PlackHandlerNetty.java) - Single-threaded Netty event loop
- [x] Perl module (Plack::Handler::Netty.pm) - Standard Plack::Handler interface with XSLoader
- [x] PSGI v1.1 environment construction from HTTP requests
- [x] PSGI [status, headers, body] response handling
- [x] HTTP/1.1 with keep-alive support
- [x] Comprehensive error handling
- [x] Test application with multiple endpoints
- [x] Documentation (README.md, POD, examples)

**PR #662**: All Phase 1 work merged to master.

### Phase 2: Dancer2 Integration 🚧 PENDING

**Goal**: Run a real Dancer2 application on Plack::Handler::Netty.

This phase will validate that the implementation works with a real framework before
moving to more complex features like streaming.

**Recommended next step**: Create a Dancer2 example application to test:
- Framework routing
- PSGI environment consumption
- Parameter extraction
- Response generation

Once Dancer2 works, the same handler automatically supports other PSGI frameworks.

### Phase 3+: Advanced Features (Future)

- **Streaming responses** - PSGI streaming callback support
- **Delayed responses** - Async response generation
- **HTTPS/TLS** - SSL termination via Netty SslHandler
- **Production features** - Graceful shutdown, metrics, performance tuning

## Background

This project implements a high-performance PSGI server handler that bridges Perl web
frameworks (Dancer2, Catalyst, Mojolicious) to Java's Netty HTTP server. By creating
`Plack::Handler::Netty`, we enable **any PSGI-compatible Perl web application** to run
on PerlOnJava with Netty's async I/O performance.

### Why Dancer2?

Dancer2 is chosen as the **first target framework** for several strategic reasons:

| Reason | Details |
|--------|---------|
| **Simplest to test** | Minimal boilerplate; 5-line apps possible |
| **Pure Perl** | No XS dependencies in core; 96% compatible Moo foundation |
| **PSGI native** | Built on Plack from the ground up |
| **Already ported** | Dancer2 2.1.0 installed on PerlOnJava (see `dancer2_support.md`) |
| **Good documentation** | Clear PSGI contract, well-understood behavior |
| **Active ecosystem** | Second most popular modern Perl web framework |

Dancer2's simplicity makes it ideal for validating the Plack::Handler::Netty adapter
layer before tackling heavier frameworks. Once Dancer2 works, the same handler
automatically supports Catalyst and Mojolicious (when run via PSGI mode).

### Why Netty?

Netty is the optimal backend for a JVM-based Perl web server:

| Benefit | Description |
|---------|-------------|
| **Battle-tested** | Used by Twitter, Apple, Facebook; powers Elasticsearch, Cassandra |
| **Async I/O** | Non-blocking event loop handles 10k+ concurrent connections |
| **Single-threaded compatible** | **CRITICAL**: PerlOnJava doesn't support threads/fork yet; Netty's single event loop model is perfect |
| **HTTP/2 support** | Modern protocol support for free |
| **WebSocket native** | First-class support for real-time apps |
| **Zero dependencies** | Single JAR (netty-all), no native libs required |
| **Active development** | Regular releases, strong Java ecosystem |

The existing `examples/http_server/` prototype already demonstrates Netty + PerlOnJava
integration with a custom request handler, using a **single-threaded event loop** to
avoid race conditions in PerlOnJava's global state. Plack::Handler::Netty **standardizes**
this by implementing the PSGI interface.

**Concurrency Model**: Uses Netty's async I/O to handle multiple concurrent connections
on a single thread. CPU-bound handlers can block other requests, but I/O-bound apps
(most web apps) work efficiently.

### Why PSGI/Plack?

PSGI (Perl Web Server Gateway Interface) is Perl's equivalent of Python's WSGI or
Ruby's Rack. By targeting PSGI, we get:

- **Framework independence** - One handler serves Dancer2, Catalyst, Mojolicious, etc.
- **Middleware ecosystem** - 100+ Plack middleware modules (auth, logging, sessions, CORS)
- **Tooling compatibility** - Works with `plackup`, `Plack::Test`, deployment tools
- **Future-proof** - New frameworks will work automatically if PSGI-compatible

## Architecture

### Overall Flow

```
┌─────────────────────────────────────────────────────────────────┐
│                     Browser / HTTP Client                        │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP Request
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              Netty HTTP Server (Java, async I/O)                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Single Event Loop Thread (or thread pool)                │  │
│  │  - Handles all I/O without blocking                       │  │
│  │  - Concurrent connections via NIO                         │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │ HttpRequest (Java object)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         Plack::Handler::Netty (Perl adapter layer)               │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  http_request_to_env(\%env) - convert Java → PSGI hash   │  │
│  │  psgi_response_to_http($res) - convert PSGI → Java       │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │ $env hashref (PSGI environment)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                    PSGI Application ($app)                       │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │  Dancer2 / Catalyst / Mojolicious (PSGI mode)            │  │
│  │  - Routes request to controllers                         │  │
│  │  - Generates response                                    │  │
│  │  - Returns PSGI response array/coderef                   │  │
│  └───────────────────────────────────────────────────────────┘  │
└────────────────────────────┬────────────────────────────────────┘
                             │ [status, headers, body]
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│         Plack::Handler::Netty (response conversion)              │
└────────────────────────────┬────────────────────────────────────┘
                             │ HttpResponse (Java object)
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│              Netty HTTP Server (sends response)                  │
└─────────────────────────────────────────────────────────────────┘
```

### PSGI Environment Hash Mapping

The PSGI specification (v1.1) defines a standard environment hash. Plack::Handler::Netty
must construct this hash from Netty's `io.netty.handler.codec.http.HttpRequest` object:

| PSGI Key | Source | Example |
|----------|--------|---------|
| REQUEST_METHOD | `request.method().name()` | `"GET"` |
| SCRIPT_NAME | Empty or mount point | `""` |
| PATH_INFO | `new QueryStringDecoder(uri).path()` | `"/users/123"` |
| REQUEST_URI | `request.uri()` | `"/users/123?sort=name"` |
| QUERY_STRING | `new QueryStringDecoder(uri).parameters()` | `"sort=name"` |
| SERVER_NAME | `Host` header or listen address | `"localhost"` |
| SERVER_PORT | `Host` header or listen port | `8080` |
| SERVER_PROTOCOL | `request.protocolVersion()` | `"HTTP/1.1"` |
| CONTENT_LENGTH | `Content-Length` header | `"1234"` |
| CONTENT_TYPE | `Content-Type` header | `"application/json"` |
| HTTP_* | All headers (uppercased, `-` → `_`) | `HTTP_USER_AGENT => "curl/7.64"` |
| psgi.version | PSGI spec version | `[1, 1]` |
| psgi.url_scheme | `http` or `https` | `"http"` |
| psgi.input | Body stream | `IO::Handle` wrapping body bytes |
| psgi.errors | Error log handle | `*STDERR` |
| psgi.multithread | Boolean | `\0` (PerlOnJava doesn't support threads) |
| psgi.multiprocess | Boolean | `\0` (PerlOnJava doesn't support fork) |
| psgi.run_once | Boolean | `\0` (persistent server) |
| psgi.nonblocking | Boolean | `\1` (Netty is async) |
| psgi.streaming | Boolean | `\1` (supports streaming) |

### PSGI Response Handling

PSGI apps return responses in three forms. Plack::Handler::Netty must handle all:

**1. Array reference (most common)**
```perl
[200, ['Content-Type' => 'text/html'], ['<html>Hello</html>']]
```
Convert to Netty `FullHttpResponse` with complete body.

**2. Streaming callback (for large responses)**
```perl
sub {
    my $responder = shift;
    $responder->([200, ['Content-Type' => 'text/plain'], $body_iterator]);
}
```
Use Netty's `HttpChunkedInput` for chunked transfer encoding.

**3. Delayed response (for async apps)**
```perl
sub {
    my $responder = shift;
    # Call $responder->(...) later when response is ready
}
```
Return control to Netty event loop; invoke `ctx.write()` when responder is called.

## Existing Prototype

The `examples/http_server/` directory already contains a working Netty + PerlOnJava
HTTP server:

| File | Purpose |
|------|---------|
| `HttpServerExample.java` | Netty server with `HttpRequestHandler` inner class |
| `handler.pl` | Perl request handler (custom format, not PSGI) |
| `Makefile` | Build and run targets |
| `README.md` | Documentation of current implementation |

**Key differences between prototype and Plack::Handler::Netty:**

| Aspect | Prototype | Plack::Handler::Netty |
|--------|-----------|----------------------|
| Interface | Custom hash format | Standard PSGI environment |
| Framework | None (raw Perl) | Any PSGI framework (Dancer2, etc.) |
| Response format | `{status, content_type, body}` | PSGI array `[status, headers, body]` |
| Streaming | Not supported | Full PSGI streaming support |
| Middleware | Not possible | All Plack middleware works |
| Reusability | One-off example | Production-ready handler |

The prototype demonstrates that:
- ✅ Netty + PerlOnJava integration is stable
- ✅ Single-threaded event loop avoids PerlOnJava thread-safety issues
- ✅ Request hash can be passed from Java to Perl efficiently
- ✅ Response hash can be returned from Perl to Java
- ✅ `RuntimeHash.createHashRef()` is the correct API for hash construction

## Implementation Plan

### Phase 1: Core PSGI Handler (Week 1)

**Goal**: Implement basic Plack::Handler::Netty with synchronous response support.

#### Task 1.1: Refactor Java Server
**File**: `src/main/java/org/perlonjava/plack/NettyPSGIServer.java` (new)

Based on `examples/http_server/HttpServerExample.java`, create a reusable PSGI server:

```java
package org.perlonjava.plack;

public class NettyPSGIServer {
    private final int port;
    private final PerlRuntime perlRuntime;
    private final RuntimeScalar psgiApp; // $app coderef

    public NettyPSGIServer(int port, RuntimeScalar psgiApp) {
        this.port = port;
        this.psgiApp = psgiApp;
        this.perlRuntime = PerlRuntime.getCurrent();
    }

    public void start() throws InterruptedException {
        // Netty server setup (single event loop thread)
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        EventLoopGroup workerGroup = new NioEventLoopGroup(1);
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             .childHandler(new PSGIRequestHandler(psgiApp));
            // ... bind and start
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class PSGIRequestHandler extends ChannelInboundHandlerAdapter {
        // Convert HttpRequest → PSGI env → call $app → convert response
    }

    private RuntimeHash buildPSGIEnv(HttpRequest request) {
        // Construct %env from request
    }

    private FullHttpResponse buildHttpResponse(RuntimeList psgiResponse) {
        // Convert [status, headers, body] → HttpResponse
    }
}
```

**Key changes from prototype:**
- Extract reusable server class (not an example program)
- Accept PSGI `$app` coderef as constructor argument
- Implement full PSGI env hash construction
- Handle PSGI array response format
- **Use single-threaded event loop** (NioEventLoopGroup(1)) to avoid thread-safety issues

**Estimated effort**: 2 days

#### Task 1.2: Implement Plack::Handler::Netty Perl Module
**File**: `src/main/perl/lib/Plack/Handler/Netty.pm` (new)

```perl
package Plack::Handler::Netty;
use strict;
use warnings;

sub new {
    my ($class, %args) = @_;
    bless {
        host => $args{host} || '0.0.0.0',
        port => $args{port} || 5000,
    }, $class;
}

sub run {
    my ($self, $app) = @_;
    
    # Load Java server class
    require Java::Inline;
    Java::Inline::import('org.perlonjava.plack.NettyPSGIServer');
    
    # Start Netty server with PSGI app
    my $server = NettyPSGIServer->new($self->{port}, $app);
    $server->start();
}

1;
```

**Estimated effort**: 1 day

#### Task 1.3: Update Plack::Loader Registry
**File**: `~/.perlonjava/lib/Plack/Loader.pm` (check if exists, else create stub)

Ensure Plack::Loader can find our handler:

```perl
# In Plack::Loader::_try_load_server_class
# Should already work if Plack/Handler/Netty.pm is in @INC
```

**Estimated effort**: 30 minutes

#### Task 1.4: Write Basic Tests
**File**: `dev/sandbox/http_server/test_netty_handler.pl` (new)

```perl
#!/usr/bin/env perl
use strict;
use warnings;
use Plack::Handler::Netty;

# Minimal PSGI app
my $app = sub {
    my ($env) = @_;
    return [
        200,
        ['Content-Type' => 'text/plain'],
        ["Hello from $env->{PATH_INFO}"]
    ];
};

my $handler = Plack::Handler::Netty->new(port => 5000);
$handler->run($app);
```

Test with:
```bash
./jperl dev/sandbox/http_server/test_netty_handler.pl &
curl http://localhost:5000/test
```

**Estimated effort**: 1 day (including debugging)

### Phase 2: Dancer2 Integration (Week 2)

**Goal**: Run a real Dancer2 application on Plack::Handler::Netty.

#### Task 2.1: Create Dancer2 Test App
**File**: `dev/sandbox/http_server/dancer_app.pl` (new)

```perl
#!/usr/bin/env perl
use Dancer2;

get '/' => sub {
    return 'Hello from Dancer2 on Netty!';
};

get '/user/:id' => sub {
    my $id = route_parameters->get('id');
    return "User: $id";
};

post '/form' => sub {
    my $name = body_parameters->get('name');
    return "Received: $name";
};

start;
```

Run with:
```bash
plackup -s Netty -p 5000 dev/sandbox/http_server/dancer_app.pl
```

**Estimated effort**: 1 day

#### Task 2.2: Test Dancer2 Features
**File**: `dev/sandbox/http_server/test_dancer.t` (new)

Use `Plack::Test` to verify:
- GET route with parameters
- POST form data
- JSON API endpoints
- Session handling
- Template rendering (if Template::Tiny works)

```perl
use Test::More;
use Plack::Test;
use Plack::Util;
use HTTP::Request::Common;

my $app = Plack::Util::load_psgi('dancer_app.pl');
test_psgi $app, sub {
    my $cb = shift;
    my $res = $cb->(GET '/');
    is $res->code, 200;
    like $res->content, qr/Hello from Dancer2/;
};

done_testing;
```

**Estimated effort**: 2 days

### Phase 3: Streaming and Advanced PSGI (Week 3)

**Goal**: Support streaming responses and delayed responses for async apps.

#### Task 3.1: Implement Streaming Responder
**Java changes**: Modify `PSGIRequestHandler` to detect code-ref responses and
handle `$responder->([status, headers, $iterator])` callback.

```java
// In PSGIRequestHandler.channelRead0()
RuntimeScalar response = RuntimeCode.apply(psgiApp, args, RuntimeContextType.SCALAR);

if (response.type == RuntimeScalarType.CODE) {
    // Streaming response - create responder callback
    RuntimeScalar responder = new RuntimeCode(...);
    RuntimeCode.apply(response, new RuntimeArray(responder), RuntimeContextType.VOID);
} else {
    // Normal array response
    RuntimeList res = response.undefOr(new RuntimeList());
    FullHttpResponse httpResponse = buildHttpResponse(res);
    ctx.writeAndFlush(httpResponse);
}
```

**Estimated effort**: 3 days

#### Task 3.2: Test Streaming
**File**: `dev/sandbox/http_server/test_streaming.pl` (new)

```perl
my $app = sub {
    my ($env) = @_;
    return sub {
        my $responder = shift;
        $responder->([
            200,
            ['Content-Type' => 'text/plain'],
            [map { "Line $_\n" } 1..1000]
        ]);
    };
};
```

**Estimated effort**: 1 day

### Phase 4: Production Features (Week 4)

**Goal**: Add features needed for production deployment.

#### Task 4.1: Configuration Options
Add to `Plack::Handler::Netty->new(%args)`:
- `backlog` - TCP backlog queue size
- `keepalive` - HTTP keep-alive timeout
- `max_request_size` - Request body size limit
- ~~`workers`~~ - **Not supported**: PerlOnJava doesn't support threads/fork yet; always single-threaded

**Estimated effort**: 1 day

#### Task 4.2: Error Handling
- Catch Perl exceptions in PSGI app, return 500 error page
- Log Java exceptions with stack traces
- Graceful shutdown on SIGTERM

**Estimated effort**: 1 day

#### Task 4.3: Performance Testing
Benchmark against:
- Starman (forking Perl server)
- Plack::Handler::Standalone (pure Perl)
- Raw Netty (Java-only)

```bash
# Using wrk or ab
wrk -t4 -c100 -d30s http://localhost:5000/
```

**Estimated effort**: 1 day

#### Task 4.4: Documentation
**File**: `src/main/perl/lib/Plack/Handler/Netty.pm` (update POD)

Add:
- SYNOPSIS with examples
- CONFIGURATION section
- PERFORMANCE notes
- CAVEATS (single-threaded, no fork support)
- SEE ALSO links

**Estimated effort**: 1 day

### Phase 5: Open Pull Request (Week 5)

**Goal**: Get code reviewed and merged into PerlOnJava main branch.

#### Task 5.1: Prepare PR
- [ ] All tests pass (`make` succeeds)
- [ ] New tests added to `src/test/` if applicable
- [ ] Code follows PerlOnJava style (4-space indent, etc.)
- [ ] Update `CHANGELOG.md`
- [ ] Update `README.md` with Netty handler mention
- [ ] Create example in `examples/dancer2_netty/`

#### Task 5.2: Write PR Description
Template:
```markdown
## Add Plack::Handler::Netty for PSGI web server support

Implements a high-performance PSGI server handler backed by Netty, enabling
Dancer2, Catalyst, and Mojolicious applications to run on PerlOnJava.

### Changes
- New `org.perlonjava.plack.NettyPSGIServer` Java class
- New `Plack::Handler::Netty` Perl module
- Test suite in `dev/sandbox/http_server/`
- Example Dancer2 app in `examples/dancer2_netty/`

### Testing
- Minimal PSGI app: ✅ 200 OK responses
- Dancer2 integration: ✅ routes, params, templates
- Streaming responses: ✅ chunked encoding
- Performance: ~10k req/s on M1 MacBook

### Related Work
- Based on prototype in `examples/http_server/`
- Dancer2 already installed (see `dev/modules/dancer2_support.md`)
- Requires Type::Tiny scoping bug fix (#XXX)

Closes #YYY
```

**Estimated effort**: 1 day

## Extending to Other Frameworks

Once Plack::Handler::Netty is working, other frameworks become trivial.

### Catalyst Support

**Status**: Catalyst is PSGI-native since v5.8.

**How to use**:
```perl
# myapp.psgi
use MyApp::Catalyst;
MyApp::Catalyst->psgi_app;
```

Then:
```bash
plackup -s Netty myapp.psgi
```

**Dependencies**: Catalyst is complex (Moose-based) and may have unsupported deps on
PerlOnJava. Defer until Moose support improves.

**Estimated additional effort**: 0 days (handler works as-is), but Catalyst installation
may need 1-2 weeks of CPAN module fixes.

### Mojolicious Support

**Status**: Mojolicious has built-in PSGI support via `Mojo::Server::PSGI`.

**How to use**:
```perl
# myapp.psgi
use Mojolicious::Lite;
get '/' => { text => 'Hello!' };
app->start('psgi');
```

Then:
```bash
plackup -s Netty myapp.psgi
```

**Current blocker**: Mojo::IOLoop has 55/108 tests passing (see `mojo_ioloop.md`).
The PSGI mode **does not use Mojo::IOLoop** (it's synchronous), so it may work earlier
than full async Mojo support.

**Estimated additional effort**: 0 days (handler works as-is), but may need to fix
Mojo::Server::PSGI blockers (unknown until tested).

### Testing Strategy for Extended Support

| Framework | Test File | Approach |
|-----------|-----------|----------|
| Catalyst | `dev/sandbox/http_server/test_catalyst.pl` | Create minimal Catalyst app with PSGI export |
| Mojolicious | `dev/sandbox/http_server/test_mojo_psgi.pl` | Use `Mojolicious::Lite` with `start('psgi')` |

Both should be tested AFTER Plack::Handler::Netty is stable with Dancer2.

## Test Code Location

All test code and sandbox applications will be written in:
```
dev/sandbox/http_server/
├── test_netty_handler.pl     # Phase 1: Minimal PSGI app
├── test_dancer.pl             # Phase 2: Dancer2 integration
├── dancer_app.pl              # Phase 2: Sample Dancer2 app
├── test_streaming.pl          # Phase 3: Streaming response test
├── test_catalyst.pl           # Future: Catalyst support
├── test_mojo_psgi.pl          # Future: Mojolicious PSGI mode
└── README.md                  # Documentation of all tests
```

## Files to Create/Modify

### New Java Files
- `src/main/java/org/perlonjava/plack/NettyPSGIServer.java` - Main server class
- `src/main/java/org/perlonjava/plack/PSGIEnvironment.java` - PSGI env builder (optional helper)

### New Perl Files
- `src/main/perl/lib/Plack/Handler/Netty.pm` - Handler module
- `dev/sandbox/http_server/test_netty_handler.pl` - Basic test
- `dev/sandbox/http_server/test_dancer.pl` - Dancer2 test
- `dev/sandbox/http_server/dancer_app.pl` - Sample Dancer2 app
- `dev/sandbox/http_server/test_streaming.pl` - Streaming test
- `dev/sandbox/http_server/README.md` - Test documentation

### Modified Files
- `examples/http_server/README.md` - Add link to Plack::Handler::Netty
- `README.md` - Mention PSGI server support
- `CHANGELOG.md` - Document new feature

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| PSGI spec edge cases | Medium | Medium | Test with Plack::Test, refer to spec |
| Netty version compatibility | Low | High | Pin to tested version (4.1.x) in Makefile |
| Java ↔ Perl type conversion bugs | Medium | High | Extensive unit tests for env/response mapping |
| Dancer2 Type::Tiny bug still present | High | High | Fix Type::Tiny scoping bug first (dancer2_support.md Issue 3) |
| Performance worse than Starman | Medium | Medium | Profile with JFR, optimize hot paths |
| Streaming responses break event loop | Medium | High | Test with large responses, use Netty's streaming APIs correctly |
| CPU-bound handlers block all requests | High | Medium | **Known limitation**: Document that PerlOnJava is single-threaded |

## Success Metrics

| Phase | Metric | Target |
|-------|--------|--------|
| Phase 1 | Minimal PSGI app works | ✅ 200 OK from curl |
| Phase 2 | Dancer2 app works | ✅ All routes pass |
| Phase 3 | Streaming works | ✅ 1MB response streams correctly |
| Phase 4 | Performance | ≥ 5,000 req/s for "Hello World" |
| Phase 5 | PR merged | ✅ Approved and merged |

## Related Documents

- `dancer2_support.md` - Dancer2 installation status (Type::Tiny blocker)
- `mojo_ioloop.md` - Mojolicious IOLoop support (55/108 tests)
- `examples/http_server/README.md` - Existing Netty prototype
- [PSGI Specification](https://metacpan.org/pod/PSGI) - Official spec
- [Plack::Handler](https://metacpan.org/pod/Plack::Handler) - Handler interface

## Progress Tracking

### Current Status: Phase 1 - Planning (2026-05-06)

### Completed
- [x] Document creation
- [x] Architecture design
- [x] Framework comparison (Dancer2 vs Catalyst vs Mojolicious)
- [x] Task breakdown

### Next Steps
1. Fix Dancer2 Type::Tiny scoping bug (prerequisite - see `dancer2_support.md` Issue 3)
2. Create `dev/sandbox/http_server/` directory structure
3. Implement Phase 1 Task 1.1: NettyPSGIServer Java class
4. Implement Phase 1 Task 1.2: Plack::Handler::Netty Perl module
5. Test minimal PSGI app (Phase 1 Task 1.4)
