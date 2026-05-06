package Plack::Handler::Netty;

use strict;
use warnings;

our $VERSION = '0.01';

# Load the Java backend immediately when this module is loaded
require XSLoader;
XSLoader::load(__PACKAGE__);

# Java backend (org.perlonjava.runtime.perlmodule.PlackHandlerNetty) provides:
# - new(host => '...', port => ..., ...) - Perl-side factory
# - run($app) - start the server
# These are registered via XSLoader and PerlModuleBase

# PSGI streaming response helper - called by Java when app returns a streaming coderef
sub _handle_streaming_response {
    my ($streaming_coderef, $send_response_callback) = @_;

    # Create a native Perl responder coderef
    # When called with [status, headers, body], it sends the HTTP response
    my $responder = sub {
        my ($response_array) = @_;

        # Validate structure
        die "responder requires arrayref argument"
            unless ref($response_array) eq 'ARRAY';

        die "responder requires 3-element array [status, headers, body]"
            unless @$response_array == 3;

        my ($status, $headers, $body) = @$response_array;

        die "status must be numeric"
            unless defined $status && $status =~ /^\d+$/;

        die "headers must be arrayref"
            unless ref($headers) eq 'ARRAY';

        die "body must be arrayref"
            unless ref($body) eq 'ARRAY';

        # Call back to Java to send the HTTP response
        $send_response_callback->([
            $status,
            $headers,
            $body,
        ]);
    };

    # Invoke the app's streaming function with our responder
    $streaming_coderef->($responder);
}

1;

__END__

=head1 NAME

Plack::Handler::Netty - High-performance PSGI server handler using Netty

=head1 SYNOPSIS

    # Standalone usage with defaults
    use Plack::Handler::Netty;

    my $app = sub {
        my ($env) = @_;
        return [
            200,
            ['Content-Type' => 'text/plain'],
            ['Hello, World!']
        ];
    };

    my $handler = Plack::Handler::Netty->new();
    $handler->run($app);

    # Production configuration
    my $handler = Plack::Handler::Netty->new(
        host             => '0.0.0.0',
        port             => 8080,
        backlog          => 256,
        keepalive        => 60,
        max_request_size => 20 * 1024 * 1024,  # 20MB
    );
    $handler->run($app);

    # With plackup
    plackup -s Netty -p 5000 app.psgi

    # With environment variable
    PLACK_SERVER=Netty plackup -p 5000 app.psgi

=head1 DESCRIPTION

C<Plack::Handler::Netty> is a PSGI server handler implementation that uses
Java's Netty framework as the HTTP server backend. This handler enables any
PSGI-compatible Perl web application (Dancer2, Catalyst, Mojolicious, etc.) to
run on PerlOnJava with Netty's high-performance async I/O capabilities.

This handler is specifically designed for PerlOnJava and leverages Netty's
battle-tested HTTP server implementation, which is used by major platforms
including Twitter, Apple, and Facebook.

=head2 Key Features

=over 4

=item * B<High Performance> - 30,000+ requests/second for simple responses

=item * B<Async I/O> - Non-blocking event loop handles many concurrent connections efficiently

=item * B<Single-threaded> - Uses Netty's single event loop thread model (compatible with PerlOnJava's threading limitations)

=item * B<PSGI 1.1 compliant> - Supports standard PSGI applications and middleware

=item * B<Streaming responses> - Full support for PSGI streaming responses

=item * B<HTTP/1.1> - Keep-alive connections, proper error handling

=item * B<Production ready> - Graceful shutdown, comprehensive error logging

=back

=head2 Performance

Benchmark results on Apple Silicon (see C<examples/http_server_plack/PERFORMANCE.md>):

=over 4

=item * Hello World: 32,980 requests/second @ 100 concurrent connections

=item * JSON API: 22,461 requests/second with dynamic content

=item * Streaming: 16,312 requests/second for streaming responses

=item * Latency: <5ms average response time

=item * Reliability: Zero failures across all benchmark tests

=back

This is 2-6x faster than typical pure Perl PSGI servers (Starman, Gazelle, Twiggy).

=head2 Concurrency Model

This handler uses Netty's async I/O to handle multiple concurrent connections
on a B<single thread>. This design choice is intentional:

=over 4

=item * PerlOnJava does not currently support threads or fork()

=item * Single-threaded async I/O avoids all thread-safety issues

=item * I/O-bound applications (most web apps) work efficiently

=item * CPU-bound request handlers may block other requests

=back

For most web applications (serving HTML, JSON APIs, database-backed apps),
this model provides excellent performance since the bottleneck is typically
I/O (database queries, file reads) rather than CPU.

For CPU-intensive applications, consider offloading heavy computation to
background workers or running multiple server instances behind a load balancer.

=head1 CONSTRUCTOR

=head2 new(%options)

Creates a new Plack::Handler::Netty instance with the specified configuration.

B<Configuration Options:>

=over 4

=item * C<host> (string, default: C<0.0.0.0>)

Hostname or IP address to bind to. Use C<0.0.0.0> to listen on all interfaces,
or C<localhost>/C<127.0.0.1> to listen only on loopback.

=item * C<port> (integer, default: C<5000>)

Port number to listen on. Must be between 1 and 65535. Ports below 1024 may
require root/administrator privileges.

=item * C<backlog> (integer, default: C<128>)

TCP connection backlog queue size. This is the maximum number of pending
connections that will be queued before the server starts rejecting new
connections. Increase for high-traffic sites.

=item * C<keepalive> (integer, default: C<30>)

HTTP keep-alive timeout in seconds. Set to C<0> to disable keep-alive.
Keep-alive allows multiple HTTP requests to be sent over a single TCP
connection, improving performance for clients making multiple requests.

=item * C<max_request_size> (integer, default: C<10485760>)

Maximum HTTP request body size in bytes (default 10MB). Requests exceeding
this size will be rejected. Increase for applications that handle large
file uploads.

=back

B<Example Configurations:>

    # Development (default)
    my $dev = Plack::Handler::Netty->new();

    # Production high-traffic
    my $prod = Plack::Handler::Netty->new(
        host             => '0.0.0.0',
        port             => 80,
        backlog          => 512,
        keepalive        => 120,
        max_request_size => 50 * 1024 * 1024,  # 50MB
    );

    # API server (no keep-alive)
    my $api = Plack::Handler::Netty->new(
        port      => 8080,
        keepalive => 0,  # Disable for stateless APIs
    );

=head1 METHODS

=head2 run($app)

Starts the Netty server and runs the PSGI application. This method blocks
until the server is shut down (typically via Ctrl+C or SIGTERM).

    $handler->run($app);

The C<$app> parameter must be a PSGI application coderef that accepts an
environment hash and returns a PSGI response (array ref or streaming coderef).

The server will log startup information and handle graceful shutdown when
interrupted.

=head1 PSGI COMPLIANCE

This handler implements the PSGI 1.1 specification and supports:

=head2 Response Types

=over 4

=item * B<Array responses> - C<[status, headers, body]>

Standard synchronous responses. The body must be an arrayref of strings.

    return [200, ['Content-Type' => 'text/plain'], ['Hello, World!']];

=item * B<Streaming responses> - Callback-based streaming

For large responses or progressive rendering, return a coderef that receives
a responder callback:

    return sub {
        my $responder = shift;
        $responder->([200, ['Content-Type' => 'text/plain'], ['chunk1', 'chunk2']]);
    };

This is useful for:

=over 4

=item * Large file downloads (avoid buffering entire file)

=item * Server-sent events (SSE)

=item * Progressive HTML rendering

=item * Memory-efficient response generation

=back

=back

=head2 PSGI Environment

The handler provides all required PSGI 1.1 environment keys:

=over 4

=item * C<REQUEST_METHOD>, C<PATH_INFO>, C<QUERY_STRING>, C<REQUEST_URI>

=item * C<SERVER_NAME>, C<SERVER_PORT>, C<SERVER_PROTOCOL>

=item * C<CONTENT_LENGTH>, C<CONTENT_TYPE>

=item * C<HTTP_*> - All HTTP headers (uppercased, C<-> becomes C<_>)

=item * C<psgi.version> - [1, 1]

=item * C<psgi.url_scheme> - C<http> (or C<https> when TLS is enabled)

=item * C<psgi.input> - Request body as IO::Handle

=item * C<psgi.errors> - Error log (STDERR)

=item * C<psgi.multithread> - \0 (PerlOnJava doesn't support threads)

=item * C<psgi.multiprocess> - \0 (PerlOnJava doesn't support fork)

=item * C<psgi.run_once> - \0 (persistent server)

=item * C<psgi.nonblocking> - \1 (Netty is async)

=item * C<psgi.streaming> - \1 (streaming supported)

=back

=head1 HTTPS/TLS SUPPORT

SSL/TLS support is provided via Netty's SslHandler.

=head2 Basic Configuration

    my $handler = Plack::Handler::Netty->new(
        port     => 443,
        ssl      => 1,
        ssl_cert => '/path/to/cert.pem',
        ssl_key  => '/path/to/key.pem',
    );
    $handler->run($app);

=head2 Configuration Options

=over 4

=item * C<ssl> (boolean, default: false)

Enable HTTPS. When enabled, C<ssl_cert> and C<ssl_key> are required.

=item * C<ssl_cert> (string, required if ssl=1)

Path to SSL certificate file in PEM format.

=item * C<ssl_key> (string, required if ssl=1)

Path to SSL private key file in PEM format.

=item * C<ssl_ca> (string, optional)

Path to CA certificate for client certificate verification.
When specified, client certificates will be optionally validated.

=item * C<ssl_protocols> (arrayref, optional)

Allowed TLS protocol versions. Default: C<['TLSv1.2', 'TLSv1.3']>

    ssl_protocols => ['TLSv1.3']  # TLS 1.3 only

=item * C<ssl_ciphers> (string, optional)

Colon-separated list of allowed cipher suites.

    ssl_ciphers => 'ECDHE-RSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384'

=back

=head2 Certificate Formats

Certificates and keys must be in PEM format (ASCII-armored).

B<Self-signed for testing:>

    openssl req -x509 -newkey rsa:2048 -keyout key.pem \
        -out cert.pem -days 365 -nodes \
        -subj "/CN=localhost"

B<Let's Encrypt for production:>

    certbot certonly --standalone -d example.com

    # Certificates will be at:
    # /etc/letsencrypt/live/example.com/fullchain.pem
    # /etc/letsencrypt/live/example.com/privkey.pem

=head2 PSGI Environment

When SSL is enabled, the PSGI environment includes:

=over 4

=item * C<psgi.url_scheme> - Set to C<"https">

=item * C<HTTPS> - Set to C<"on"> (CGI standard variable)

=back

Applications can detect HTTPS:

    my $app = sub {
        my ($env) = @_;
        my $is_https = $env->{'psgi.url_scheme'} eq 'https';
        # or
        my $is_https = $env->{'HTTPS'} eq 'on';
        ...
    };

=head2 Production Deployment

For production, use proper certificates from:

=over 4

=item * B<Let's Encrypt> - Free automated certificates (recommended)

=item * B<Commercial CA> - Paid certificates with support/warranty

=back

B<Do NOT use self-signed certificates in production.>

=head2 Reverse Proxy Alternative

For some deployments, you may prefer SSL termination at a reverse proxy:

=over 4

=item * Nginx with proxy_pass (recommended)

=item * Apache with mod_proxy

=item * HAProxy with SSL termination

=back

This allows the proxy to handle SSL, certificate renewal, and HTTPS-to-HTTP
forwarding to the handler.

=head1 ERROR HANDLING

The handler provides comprehensive error handling:

=over 4

=item * Application exceptions are caught and logged with full stack traces

=item * HTTP 500 error pages are returned to clients for application errors

=item * Invalid PSGI responses generate clear error messages

=item * Graceful shutdown on SIGTERM/SIGINT

=item * Channel exceptions are logged with client address

=back

All errors are logged to STDERR for debugging.

=head1 DEPLOYMENT

=head2 Development

For development, run directly:

    ./jperl myapp.pl

Or with plackup:

    plackup -s Netty app.psgi

=head2 Production

For production deployments:

=over 4

=item 1. Run behind a reverse proxy (Nginx, HAProxy) for:

=over 4

=item * SSL/TLS termination

=item * Static file serving

=item * Load balancing across multiple instances

=item * DDoS protection and rate limiting

=back

=item 2. Run multiple instances for high availability:

    # Start 4 instances on different ports
    ./jperl app.pl --port 5001 &
    ./jperl app.pl --port 5002 &
    ./jperl app.pl --port 5003 &
    ./jperl app.pl --port 5004 &

Configure your load balancer to distribute across all instances.

=item 3. Use a process manager (systemd, supervisord) for:

=over 4

=item * Automatic restart on crashes

=item * Log rotation

=item * Resource limits

=back

=item 4. Monitor performance and errors:

=over 4

=item * Watch STDERR logs for exceptions

=item * Monitor response times and throughput

=item * Set up alerts for high error rates

=back

=back

=head1 CAVEATS

=over 4

=item * B<Single-threaded> - CPU-bound request handlers block other requests. Offload heavy computation to background workers.

=item * B<No fork support> - Cannot use pre-fork concurrency model. Use multiple processes with load balancing instead.

=item * B<PerlOnJava specific> - Requires PerlOnJava runtime, won't work with standard Perl.

=item * B<No HTTPS yet> - TLS/SSL support planned for future release. Use reverse proxy for HTTPS now.

=back

=head1 SEE ALSO

=over 4

=item * L<PSGI> - PSGI specification

=item * L<Plack> - PSGI toolkit and utilities

=item * L<Plack::Handler> - Handler interface

=item * L<plackup> - Command-line PSGI server launcher

=item * C<examples/http_server_plack/> - Example applications and performance benchmarks

=back

=head1 AUTHOR

PerlOnJava Team

=head1 LICENSE

This is free software; you can redistribute it and/or modify it under
the same terms as Perl itself.

=cut
