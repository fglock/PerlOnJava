use strict;
use warnings;
use Test::More tests => 8;
use JSON::XS qw(encode_json decode_json);

is($JSON::XS::VERSION, '4.04', 'reports the compatible JSON::XS version');
is(encode_json({ answer => 42 }), '{"answer":42}', 'functional encoder');
is_deeply(decode_json('{"items":[1,2]}'), { items => [1, 2] }, 'functional decoder');

my $json = JSON::XS->new->canonical(1)->allow_nonref(1);
isa_ok($json, 'JSON::XS');
is($json->encode({ b => 2, a => 1 }), '{"a":1,"b":2}', 'object options are inherited');
is($json->decode('42'), 42, 'object decoder honors allow_nonref');
ok(JSON::XS::is_bool(JSON::XS::true()), 'true is recognized as a JSON boolean');
ok(!JSON::XS::is_bool(0), 'ordinary scalars are not JSON booleans');
