use strict;
use warnings;
use Test::More;
use JSON::SL qw(decode_json);

is_deeply(decode_json('{"items":[1,2]}'), { items => [1, 2] },
    'decode_json uses the bundled JSON decoder');
my $boolean = decode_json('{"enabled":true}')->{enabled};
isa_ok($boolean, 'JSON::SL::Boolean', 'JSON booleans use JSON::SL::Boolean');
ok($boolean, 'true JSON boolean retains true value');
eval { decode_json('{"id":"') };
like($@, qr/incomplete/i, 'truncated JSON reports an incomplete input');

my $sl = JSON::SL->new;
$sl->set_jsonpointer(['/^/value']);
my @first = $sl->feed('{"one":{"value":1},');
is_deeply([map { [$_->{Path}, $_->{Value}] } @first], [['/one/value', 1]],
    'a completed subtree is delivered before the enclosing document ends');
my @results = $sl->feed('"two":{"value":2}}');
is_deeply(
    [map { [$_->{Path}, $_->{JSONPointer}, $_->{Value}] } @results],
    [['/two/value', '/^/value', 2]],
    'completed JSON values are selected by JSON Pointer wildcard',
);

done_testing;
