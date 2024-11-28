use strict;
use warnings;
use HTTP::Tiny;
use HTTP::CookieJar;

# Create a new HTTP::Tiny object
my $http = HTTP::Tiny->new( cookie_jar => HTTP::CookieJar->new );

# Define the URL to request
my $url = 'http://www.test.com';

# Perform a GET request
my $response = $http->get($url);

# Check if the request was successful
if ( $response->{success} ) {
    print "Response Status: $response->{status}\n";
    print "Response Content:\n$response->{content}\n";
}
else {
    warn "Failed to get $url: $response->{status} $response->{reason}\n";
}

# Access the cookie jar
my $cookie_jar = $http->{cookie_jar};

# Print cookies for the domain
my $cookies = $cookie_jar->cookies_for($url);
if ($cookies) {
    print "Cookies:\n";
    foreach my $cookie ( keys %$cookies ) {
        print "$cookie: $cookies->{$cookie}\n";
    }
}
else {
    print "No cookies\n";
}
