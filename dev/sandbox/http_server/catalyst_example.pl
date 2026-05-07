#!/usr/bin/env perl
use strict;
use warnings;

# Simple Catalyst-like app using Catalyst::Runtime
# Testing framework compatibility with Netty

print "Testing Catalyst::Runtime with Plack::Handler::Netty\n\n";

eval {
    # Use Catalyst::Runtime which is available
    require Catalyst::Runtime;
    require Plack::Handler::Netty;

    print "✓ Catalyst::Runtime loaded\n";
    print "✓ Plack::Handler::Netty loaded\n";

    # Create a simple PSGI app (Catalyst-style)
    my $app = sub {
        my ($env) = @_;

        my $path = $env->{PATH_INFO};
        my $method = $env->{REQUEST_METHOD};

        if ($path eq '/') {
            return [200, ['Content-Type' => 'text/plain'],
                    ['Hello from Catalyst-Runtime on Netty!']];
        }
        elsif ($path =~ m{^/hello/(.+)}) {
            my $name = $1;
            return [200, ['Content-Type' => 'text/plain'],
                    ["Hello, $name!"]];
        }
        else {
            return [404, ['Content-Type' => 'text/plain'],
                    ["Not found: $path"]];
        }
    };

    print "✓ PSGI app created\n";

    my $handler = Plack::Handler::Netty->new(
        host => '127.0.0.1',
        port => 6401,
    );

    print "✓ Netty handler initialized\n";
    print "\nStarting server on port 6401...\n";
    print "Press Ctrl+C to stop\n\n";

    $handler->run($app);
};

if ($@) {
    print "✗ Error: $@\n";
    exit 1;
}
