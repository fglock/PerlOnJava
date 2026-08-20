use strict;
use warnings;
use Test::More tests => 7;

my $calls = 0;
my $defined = eval q{
    sub interpreter_eval_named_sub_lexical_mutation {
        return ++$calls;
    }
    1;
};

ok($defined, 'eval STRING defines a named closure') or diag($@);
is($calls, 0, 'captured lexical starts unchanged');
is(interpreter_eval_named_sub_lexical_mutation(), 1,
    'named closure returns its first increment');
is($calls, 1, 'named closure publishes its first lexical mutation');

$calls = 40;
is(interpreter_eval_named_sub_lexical_mutation(), 41,
    'named closure observes a later outer lexical mutation');
is($calls, 41, 'named closure publishes a later lexical mutation');
is(interpreter_eval_named_sub_lexical_mutation(), 42,
    'named closure retains the shared lexical cell across calls');
