#!/usr/bin/env perl
use strict;
use warnings;

=head1 NAME

test_streaming.pl - Test PSGI streaming responses with Plack::Handler::Netty

This test application demonstrates streaming responses where the app returns
a coderef that invokes a responder with [status, headers, body]. This is an
alternative to synchronous array responses.

Three test routes:
- GET / - Basic streaming response
- GET /large - Large response (1000 lines) to verify no buffering
- GET /sync - Synchronous response (for comparison)

=cut

use Plack::Handler::Netty;

# PSGI app that supports both sync and streaming responses
my $app = sub {
    my ($env) = @_;
    my $path = $env->{PATH_INFO};

    # Route 1: Streaming response
    if ($path eq '/' || $path eq '') {
        return sub {
            my $responder = shift;
            $responder->([
                200,
                ['Content-Type' => 'text/plain'],
                ['Hello ', 'from ', 'streaming!']
            ]);
        };
    }

    # Route 2: Large streaming response (verify no buffering)
    if ($path eq '/large') {
        return sub {
            my $responder = shift;
            my @lines = map { "Line $_\n" } 1..1000;
            $responder->([
                200,
                ['Content-Type' => 'text/plain'],
                \@lines
            ]);
        };
    }

    # Route 3: Synchronous response (traditional)
    if ($path eq '/sync') {
        return [
            200,
            ['Content-Type' => 'text/plain'],
            ['Sync response']
        ];
    }

    # Route 4: JSON streaming
    if ($path eq '/json') {
        return sub {
            my $responder = shift;
            $responder->([
                200,
                ['Content-Type' => 'application/json'],
                ['{"status":"ok","message":"Streaming JSON"}']
            ]);
        };
    }

    # 404
    return [
        404,
        ['Content-Type' => 'text/plain'],
        ['Not found']
    ];
};

print STDERR "Test Streaming Response Server\n";
print STDERR "================================\n\n";
print STDERR "Test endpoints:\n";
print STDERR "  GET http://localhost:5000/          - Streaming response\n";
print STDERR "  GET http://localhost:5000/large     - Large streaming response\n";
print STDERR "  GET http://localhost:5000/sync      - Synchronous response\n";
print STDERR "  GET http://localhost:5000/json      - JSON streaming\n\n";

my $handler = Plack::Handler::Netty->new(
    host => '0.0.0.0',
    port => 5000,
);

$handler->run($app);
