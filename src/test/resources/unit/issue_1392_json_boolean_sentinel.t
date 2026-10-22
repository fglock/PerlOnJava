use strict;
use warnings;
use Test::More;
use JSON;

my $decoded = JSON->new->decode('{"copyable":true,"hidden":false}');

ok(defined $JSON::true, '$JSON::true remains defined after decoding JSON true');
ok($JSON::true, '$JSON::true is truthy');
is($decoded->{copyable}, $JSON::true,
    'decoded JSON true compares equal to the documented sentinel');
is($decoded->{hidden}, $JSON::false,
    'decoded JSON false compares equal to the documented sentinel');

done_testing;
