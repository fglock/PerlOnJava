use strict;
use warnings;
use Test::More tests => 6;

use HTTP::Cookies;

my $jar = HTTP::Cookies->new;
$jar->set_cookie(1, 'session', 'value with spaces', '/private', 'example.test',
    '443', 1, 1, undef, 1);

my $serialized = $jar->as_string;
like($serialized, qr/^Set-Cookie3: session="value with spaces";/,
    'quotes cookie values containing spaces');
like($serialized, qr/; path="\/private";/, 'serializes the cookie path');
like($serialized, qr/; domain=example\.test;/, 'serializes the cookie domain');
like($serialized, qr/; port=443; path_spec; secure; discard; version=1\n\z/,
    'serializes cookie attributes in HTTP::Cookies order');
is($jar->as_string(1), '', 'can omit discard cookies');

$jar->set_cookie(0, 'plain', 'value', '/', 'example.test');
like($jar->as_string, qr/^Set-Cookie3: plain=value; path="\/"; domain=example\.test; version=0$/m,
    'serializes a plain version zero cookie');
