#!/usr/bin/env perl
use strict;
use warnings;

# Dancer2 example with Plack::Handler::Netty
#
# This demonstrates that Dancer2 works seamlessly with the Netty handler.
# Run with: ./jperl examples/http_server_plack/dancer_example.pl

use Dancer2;

get '/' => sub {
    return 'Hello from Dancer2 on Netty!';
};

get '/hello/:name' => sub {
    my $name = route_parameters->get('name');
    return "Hello, $name!";
};

get '/echo' => sub {
    my $msg = query_parameters->get('msg') || 'nothing';
    return "You sent: $msg";
};

get '/env/:key' => sub {
    my $key = route_parameters->get('key');
    # Access PSGI environment
    my $env = request->env;
    my $value = $env->{$key} // 'not found';
    return "env{$key} = $value";
};

# Start the server (will use Netty if PLACK_SERVER=Netty or via plackup -s Netty)
start;
