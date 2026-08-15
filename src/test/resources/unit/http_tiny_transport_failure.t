use strict;
use warnings;
use Test::More tests => 4;
use HTTP::Tiny;

my $response = HTTP::Tiny->new(timeout => 1)->get('http://127.0.0.1:1/');
ok(!$response->{success}, 'transport failure is not successful');
is($response->{status}, 599, 'transport failure uses HTTP::Tiny status 599');
is($response->{reason}, 'Internal Exception', 'transport failure has standard reason');
ok(exists $response->{content}, 'transport failure includes diagnostic content');
