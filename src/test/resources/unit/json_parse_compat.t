use strict;
use warnings;
use Test::More tests => 11;
use JSON::Parse qw(parse_json parse_json_safe valid_json assert_valid_json);

is($JSON::Parse::VERSION, '0.62', 'reports compatible JSON::Parse version');
is_deeply(parse_json('{"items":[1,true,false,null]}'),
    { items => [1, 1, 0, undef] }, 'parses JSON through the bundled backend');
ok(valid_json('{"answer":42}'), 'valid_json accepts JSON');
ok(!valid_json('{"answer":}'), 'valid_json rejects malformed JSON');
eval { assert_valid_json('') };
like($@, qr/empty input/i, 'empty input has a JSON::Parse-compatible failure');

my $safe_warning;
local $SIG{__WARN__} = sub { $safe_warning = shift };
is(parse_json_safe('{"a":1,"a":2}'), undef, 'safe parser returns undef for duplicate keys');
like($safe_warning, qr/Name is not unique/, 'safe parser warns about duplicate keys');

my $parser = JSON::Parse->new;
is($parser->get_max_depth, 10_000, 'default maximum depth matches JSON::Parse');
$parser->set_max_depth(1);
is($parser->get_max_depth, 1, 'set_max_depth is retained');
eval { $parser->parse('[[1]]') };
like($@, qr/(?:max.depth|maximum nesting)/i, 'maximum depth limits decoding');

$parser->set_true('yes');
$parser->set_false('no');
$parser->set_null('none');
is_deeply($parser->parse('[true,false,null]'), ['yes', 'no', 'none'],
    'custom literals are applied recursively');
