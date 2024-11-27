use HTTP::Tiny;

# Create a new HTTP::Tiny instance
my $http = HTTP::Tiny->new();

# Perform a GET request
my $response = $http->get('https://www.example.com');

# Check if the request was successful
if ($response->{success}) {
    print "Response content:\n";
    print $response->{content};
} else {
    print "Failed to fetch the URL. Status: $response->{status}\n";
    print "Reason: $response->{reason}\n";
}

