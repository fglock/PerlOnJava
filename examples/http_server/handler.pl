#!/usr/bin/env perl
use strict;
use warnings;

# HTTP Request Handler for PerlOnJava Web Server
#
# This subroutine is called for every HTTP request.
# It receives a hash reference with request information and
# returns a hash reference with the response.
#
# Request hash structure:
#   {
#     method   => 'GET' | 'POST' | 'PUT' | etc.
#     path     => '/api/users' (without query string)
#     uri      => '/api/users?id=123' (full URI)
#     body     => 'request body content'
#     query    => { param1 => 'value1', ... }
#     headers  => { 'content-type' => 'text/html', ... }
#   }
#
# Response hash structure:
#   {
#     status       => 200,
#     content_type => 'text/html',
#     body         => '<html>...</html>'
#   }

sub handle_request {
    my ($request) = @_;

    my $method = $request->{method};
    my $path = $request->{path};
    my $query = $request->{query};

    # Route dispatcher
    if ($path eq '/') {
        return handle_home($request);
    }
    elsif ($path =~ m{^/api/(.+)}) {
        return handle_api($1, $request);
    }
    elsif ($path eq '/form') {
        return handle_form($request);
    }
    elsif ($path eq '/time') {
        return handle_time($request);
    }
    elsif ($path eq '/env') {
        return handle_env($request);
    }
    elsif ($path eq '/echo') {
        return handle_echo($request);
    }
    else {
        return handle_404($path);
    }
}

# Home page handler
sub handle_home {
    my ($request) = @_;

    my $html = <<'HTML';
<!DOCTYPE html>
<html>
<head>
    <title>PerlOnJava HTTP Server</title>
    <style>
        body { font-family: Arial, sans-serif; max-width: 800px; margin: 50px auto; padding: 20px; }
        h1 { color: #333; }
        .endpoint { background: #f4f4f4; padding: 10px; margin: 10px 0; border-left: 4px solid #0066cc; }
        code { background: #e8e8e8; padding: 2px 6px; border-radius: 3px; }
    </style>
</head>
<body>
    <h1>Welcome to PerlOnJava HTTP Server!</h1>
    <p>This web server is powered by <strong>Netty</strong> and <strong>PerlOnJava</strong>.</p>
    <p>Request handlers are written in Perl for easy customization.</p>

    <h2>Available Endpoints:</h2>

    <div class="endpoint">
        <strong>GET /</strong><br>
        This page (home)
    </div>

    <div class="endpoint">
        <strong>GET /api/&lt;resource&gt;</strong><br>
        API endpoint example<br>
        Try: <a href="/api/users">/api/users</a>, <a href="/api/products">/api/products</a>
    </div>

    <div class="endpoint">
        <strong>POST /form</strong><br>
        Form submission example<br>
        <code>curl -X POST http://localhost:8080/form -d "name=Alice&amp;age=30"</code>
    </div>

    <div class="endpoint">
        <strong>GET /time</strong><br>
        Current server time<br>
        <a href="/time">Click here</a>
    </div>

    <div class="endpoint">
        <strong>GET /env</strong><br>
        Server environment information<br>
        <a href="/env">Click here</a>
    </div>

    <div class="endpoint">
        <strong>GET /echo?message=hello</strong><br>
        Echo back query parameters<br>
        <a href="/echo?message=Hello+World&amp;count=3">Try it</a>
    </div>

    <hr>
    <p><small>Powered by PerlOnJava 5.42.0 | Thread-safe via single event loop</small></p>
</body>
</html>
HTML

    return {
        status => 200,
        content_type => 'text/html',
        body => $html
    };
}

# API handler
sub handle_api {
    my ($resource, $request) = @_;

    # Simulate an API response
    my $response_data;

    if ($resource eq 'users') {
        $response_data = {
            users => [
                { id => 1, name => 'Alice', email => 'alice@example.com' },
                { id => 2, name => 'Bob', email => 'bob@example.com' },
                { id => 3, name => 'Charlie', email => 'charlie@example.com' }
            ]
        };
    }
    elsif ($resource eq 'products') {
        $response_data = {
            products => [
                { id => 101, name => 'Widget', price => 19.99 },
                { id => 102, name => 'Gadget', price => 29.99 },
                { id => 103, name => 'Doohickey', price => 9.99 }
            ]
        };
    }
    else {
        return {
            status => 404,
            content_type => 'application/json',
            body => qq({"error":"Resource not found: $resource"})
        };
    }

    # Simple JSON serialization (for a real app, use JSON::PP or similar)
    require Data::Dumper;
    my $json = format_as_json($response_data);

    return {
        status => 200,
        content_type => 'application/json',
        body => $json
    };
}

# Form handler
sub handle_form {
    my ($request) = @_;

    if ($request->{method} ne 'POST') {
        return {
            status => 405,
            content_type => 'text/plain',
            body => 'Method Not Allowed. Use POST.'
        };
    }

    my $body = $request->{body};

    # Parse form data (simple implementation)
    my %form_data;
    foreach my $pair (split /&/, $body) {
        my ($key, $value) = split /=/, $pair, 2;
        $key = uri_unescape($key);
        $value = uri_unescape($value);
        $form_data{$key} = $value;
    }

    my $html = "<html><body><h1>Form Received</h1><pre>";
    foreach my $key (sort keys %form_data) {
        $html .= "$key = $form_data{$key}\n";
    }
    $html .= "</pre></body></html>";

    return {
        status => 200,
        content_type => 'text/html',
        body => $html
    };
}

# Time handler
sub handle_time {
    my ($request) = @_;

    my $time = localtime();
    my $epoch = time();

    my $html = <<HTML;
<!DOCTYPE html>
<html>
<head><title>Server Time</title></head>
<body>
    <h1>Current Server Time</h1>
    <p><strong>Local Time:</strong> $time</p>
    <p><strong>Unix Epoch:</strong> $epoch</p>
    <p><a href="/">Back to Home</a></p>
</body>
</html>
HTML

    return {
        status => 200,
        content_type => 'text/html',
        body => $html
    };
}

# Environment handler
sub handle_env {
    my ($request) = @_;

    my $html = <<'HTML';
<!DOCTYPE html>
<html>
<head><title>Environment</title></head>
<body>
    <h1>Server Environment</h1>
    <h2>Request Information</h2>
    <pre>
HTML

    $html .= "Method: " . $request->{method} . "\n";
    $html .= "Path: " . $request->{path} . "\n";
    $html .= "URI: " . $request->{uri} . "\n";

    $html .= "\nHeaders:\n";
    foreach my $key (sort keys %{$request->{headers}}) {
        $html .= "  $key: " . $request->{headers}{$key} . "\n";
    }

    if (%{$request->{query}}) {
        $html .= "\nQuery Parameters:\n";
        foreach my $key (sort keys %{$request->{query}}) {
            $html .= "  $key: " . $request->{query}{$key} . "\n";
        }
    }

    $html .= <<'HTML';
    </pre>
    <h2>Perl Version</h2>
    <pre>
HTML

    $html .= "Perl Version: $]\n";
    $html .= "Platform: $^O\n";

    $html .= <<'HTML';
    </pre>
    <p><a href="/">Back to Home</a></p>
</body>
</html>
HTML

    return {
        status => 200,
        content_type => 'text/html',
        body => $html
    };
}

# Echo handler
sub handle_echo {
    my ($request) = @_;

    my $query = $request->{query};
    my $message = $query->{message} || 'No message provided';
    my $count = $query->{count} || 1;

    my $html = <<HTML;
<!DOCTYPE html>
<html>
<head><title>Echo</title></head>
<body>
    <h1>Echo Service</h1>
HTML

    for (my $i = 1; $i <= $count; $i++) {
        $html .= "<p>$i: $message</p>\n";
    }

    $html .= <<'HTML';
    <p><a href="/">Back to Home</a></p>
</body>
</html>
HTML

    return {
        status => 200,
        content_type => 'text/html',
        body => $html
    };
}

# 404 handler
sub handle_404 {
    my ($path) = @_;

    my $html = <<HTML;
<!DOCTYPE html>
<html>
<head><title>404 Not Found</title></head>
<body>
    <h1>404 - Not Found</h1>
    <p>The requested path <code>$path</code> was not found on this server.</p>
    <p><a href="/">Back to Home</a></p>
</body>
</html>
HTML

    return {
        status => 404,
        content_type => 'text/html',
        body => $html
    };
}

# Helper: Simple JSON formatter (for demo purposes)
sub format_as_json {
    my ($data) = @_;
    my $json = to_json($data);
    return $json;
}

sub to_json {
    my ($val) = @_;

    if (ref($val) eq 'HASH') {
        my @pairs;
        foreach my $key (keys %$val) {
            push @pairs, qq("$key":) . to_json($val->{$key});
        }
        return '{' . join(',', @pairs) . '}';
    }
    elsif (ref($val) eq 'ARRAY') {
        my @items;
        foreach my $item (@$val) {
            push @items, to_json($item);
        }
        return '[' . join(',', @items) . ']';
    }
    elsif (looks_like_number($val)) {
        return $val;
    }
    else {
        $val =~ s/\\/\\\\/g;
        $val =~ s/"/\\"/g;
        return qq("$val");
    }
}

sub looks_like_number {
    my ($val) = @_;
    return $val =~ /^-?\d+(\.\d+)?$/;
}

# Helper: Simple URL unescape
sub uri_unescape {
    my ($str) = @_;
    $str =~ s/\+/ /g;
    $str =~ s/%([0-9A-Fa-f]{2})/chr(hex($1))/eg;
    return $str;
}

# Return true to indicate successful loading
1;
