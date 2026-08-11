use strict;
use warnings;
use Test::More;
use Data::Util qw(is_value is_string);

ok(is_value('0'), 'non-reference scalar is a value');
ok(!is_value(undef), 'undef is not a value');
ok(!is_value([]), 'reference is not a value');
ok(is_string('text'), 'non-empty string is a string');
ok(!is_string(''), 'empty string is not a string');
ok(!is_string(42), 'numeric-only scalar is not a string');

my $values = [];
ok(!is_string($values->[@$values]), 'missing array element is not a string');
is(scalar(@$values), 0,
    'XS-compatible string inspection does not vivify an aliased missing slot');

done_testing;
