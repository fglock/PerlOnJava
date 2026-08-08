use strict;
use warnings;

use Future;
use Future::AsyncAwait;

my $app = async sub {
    my ($scope, $receive, $send) = @_;

    die "This example only handles HTTP requests\n"
        unless ($scope->{type} // '') eq 'http';

    my $path = $scope->{path} // '/';
    my ($status, $body) = $path eq '/'
        ? (200, "Hello from PAGI on PerlOnJava!\n")
        : (404, "Not found\n");

    await $send->({
        type    => 'http.response.start',
        status  => $status,
        headers => [
            [ 'content-type',   'text/plain; charset=utf-8' ],
            [ 'content-length', length($body) ],
        ],
    });

    await $send->({
        type => 'http.response.body',
        body => $body,
        more => 0,
    });
};

$app;
