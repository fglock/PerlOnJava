#!/usr/bin/env perl
use strict;
use warnings;
use FindBin;
use lib "$FindBin::Bin/../../../src/main/perl/lib";

# Phase 2 Test: Dancer2 application running on Plack::Handler::Netty
#
# This tests full framework integration:
# - Route parameters
# - Query parameters
# - Form data
# - JSON responses
# - Template rendering (if Template::Tiny works)

use Dancer2;

# Home page
get '/' => sub {
    return <<'HTML';
<!DOCTYPE html>
<html>
<head>
    <title>Dancer2 on Netty</title>
    <style>
        body { font-family: Arial; max-width: 800px; margin: 50px auto; }
        .route { background: #f0f0f0; padding: 10px; margin: 10px 0; }
    </style>
</head>
<body>
    <h1>Dancer2 on PerlOnJava + Netty!</h1>
    <p>This Dancer2 app is running on Plack::Handler::Netty</p>

    <h2>Test Routes:</h2>
    <div class="route">
        <strong>GET /user/:id</strong><br>
        <a href="/user/123">Try /user/123</a>
    </div>
    <div class="route">
        <strong>GET /api/users</strong><br>
        <a href="/api/users">Try it</a> (JSON response)
    </div>
    <div class="route">
        <strong>GET /search?q=keyword</strong><br>
        <a href="/search?q=dancer">Try /search?q=dancer</a>
    </div>
    <div class="route">
        <strong>POST /form</strong><br>
        curl -X POST http://localhost:5000/form -d "name=Alice&age=30"
    </div>
</body>
</html>
HTML
};

# Route with parameters
get '/user/:id' => sub {
    my $id = route_parameters->get('id');
    return "User ID: $id\n";
};

# JSON API endpoint
get '/api/users' => sub {
    content_type 'application/json';
    return to_json({
        users => [
            { id => 1, name => 'Alice', email => 'alice@example.com' },
            { id => 2, name => 'Bob', email => 'bob@example.com' },
            { id => 3, name => 'Charlie', email => 'charlie@example.com' },
        ]
    });
};

# Query parameters
get '/search' => sub {
    my $query = query_parameters->get('q') || '(no query)';
    return "Search query: $query\n";
};

# POST form handling
post '/form' => sub {
    my $name = body_parameters->get('name') || 'unknown';
    my $age = body_parameters->get('age') || 'unknown';
    return "Received: name=$name, age=$age\n";
};

# 404 handler
any qr{.*} => sub {
    status 404;
    return "Not found: " . request->path . "\n";
};

# Get PSGI app (for plackup)
to_app;
