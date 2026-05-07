# HTTP Server Test Sandbox

This directory contains test applications and examples for Plack::Handler::Netty development.

## Files

### Phase 1: Core PSGI Handler
- `test_netty_handler.pl` - Minimal PSGI app to test basic handler functionality
- `test_psgi_env.pl` - Test PSGI environment hash construction

### Phase 2: Dancer2 Integration
- `dancer_app.pl` - Sample Dancer2 application
- `test_dancer.pl` - Dancer2 integration tests
- `dancer_example.pl` - Dancer2 compatibility example moved from `examples/http_server_plack/`
- `catalyst_example.pl` - Catalyst compatibility example moved from `examples/http_server_plack/`
- `test_frameworks.pl` - Framework compatibility script moved from `examples/http_server_plack/`

### Phase 3: Streaming
- `test_streaming.pl` - Streaming response tests

### Future
- `test_catalyst.pl` - Catalyst framework test (when Catalyst is installable)
- `test_mojo_psgi.pl` - Mojolicious PSGI mode test (when Mojo::IOLoop complete)

## Running Tests

```bash
# Test minimal PSGI app
./jperl test_netty_handler.pl

# Test Dancer2 integration
./jperl test_dancer.pl

# Test from command line
./jperl -I../../src/main/perl/lib test_netty_handler.pl
```

## Prerequisites

- PerlOnJava built (`make` in project root)
- Netty JAR downloaded (see examples/http_server/Makefile)
- Dancer2 installed (`./jcpan -t Dancer2`)
- Type::Tiny scoping bug fixed (see dev/modules/dancer2_support.md)
