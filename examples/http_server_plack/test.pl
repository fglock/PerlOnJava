#!/usr/bin/env perl
use strict;
use warnings;

# Phase 1 Test: Minimal PSGI application with Plack::Handler::Netty
#
# This tests the basic PSGI interface:
# - Environment hash construction
# - Simple array response [status, headers, body]
# - HTTP GET/POST handling

print "Loading Plack::Handler::Netty...\n";

# Check if handler exists
eval { require Plack::Handler::Netty; 1 }
    or die "Plack::Handler::Netty not found. Implement it first!\n$@";

# Minimal PSGI app
my $app = sub {
    my ($env) = @_;

    my $path = $env->{PATH_INFO};
    my $method = $env->{REQUEST_METHOD};

    # Route dispatcher
    if ($path eq '/') {
        return [
            200,
            ['Content-Type' => 'text/html'],
            ['<html><body><h1>Hello from PSGI on Netty!</h1></body></html>']
        ];
    }
    elsif ($path eq '/env') {
        # Dump PSGI environment
        my $body = "<html><body><h1>PSGI Environment</h1><pre>\n";
        for my $key (sort keys %$env) {
            my $value = $env->{$key} // '(undef)';
            $value = ref($value) if ref($value);
            $body .= "$key = $value\n";
        }
        $body .= "</pre></body></html>";
        return [200, ['Content-Type' => 'text/html'], [$body]];
    }
    elsif ($path =~ m{^/hello/(.+)}) {
        my $name = $1;
        return [
            200,
            ['Content-Type' => 'text/plain'],
            ["Hello, $name!"]
        ];
    }
    elsif ($path eq '/json') {
        return [
            200,
            ['Content-Type' => 'application/json'],
            ['{"message":"Hello from PSGI","status":"ok"}']
        ];
    }
    elsif ($method eq 'POST' && $path eq '/echo') {
        # Read POST body
        my $input = $env->{'psgi.input'};
        my $content_length = $env->{CONTENT_LENGTH} || 0;
        my $body = '';
        if ($content_length > 0) {
            $input->read($body, $content_length);
        }
        return [
            200,
            ['Content-Type' => 'text/plain'],
            ["You sent: $body"]
        ];
    }
    else {
        return [
            404,
            ['Content-Type' => 'text/plain'],
            ["Not found: $path"]
        ];
    }
};

print STDERR "Netty PSGI Server starting on 0.0.0.0:5000\n";
print STDERR "Thread model: Single event loop (async I/O)\n";
print STDERR "Press Ctrl+C to stop\n";

print "Starting server on http://localhost:5000\n";
print "Test with:\n";
print "  curl http://localhost:5000/\n";
print "  curl http://localhost:5000/hello/World\n";
print "  curl http://localhost:5000/json\n";
print "  curl http://localhost:5000/env\n";
print "  curl -X POST http://localhost:5000/echo -d 'test data'\n";
print "\n";

my $handler = Plack::Handler::Netty->new(
    host => '0.0.0.0',
    port => 5000,
);

print STDERR "Plack::Handler::Netty: Accepting connections at http://0.0.0.0:5000/\n";

$handler->run($app);
