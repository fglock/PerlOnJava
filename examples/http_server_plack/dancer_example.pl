#!/usr/bin/env perl
use strict;
use warnings;

# Dancer2 example with Plack::Handler::Netty
#
# This demonstrates that Dancer2 works seamlessly with the Netty handler.
# Run with: PLACK_SERVER=Netty plackup -p 5000 examples/http_server_plack/dancer_example.pl
#       or: ./jperl examples/http_server_plack/dancer_example.pl

# Use Plack to export the app as PSGI
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

# Export PSGI app
app->to_app;
