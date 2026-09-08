use strict;
use warnings;
use JSON::PP;
use Test::More;

# Selenium::Remote::Driver records HTTP responses as JSON strings.  Large
# escaped response bodies must decode without character-by-character quadratic
# concatenation.
my $body = ('line with a quote " and a slash \\ ' x 20_000);
my $json = JSON::PP->new->utf8->encode({ response => $body });
my $decoded = JSON::PP->new->utf8->decode($json);

is($decoded->{response}, $body,
    'large escaped JSON string decodes without changing its contents');

done_testing;
