use strict;
use warnings;
use Test::More tests => 2;

my $eval_error;
{
    local $@;
    eval <<'OUTER';
        package LexicalSubNestedEval;
        use v5.40;

        sub run_inner_eval {
            eval 'use v5.40; true';
            die $@ if $@;
        }
OUTER
    $eval_error = $@;
}

is($eval_error, '', 'lexical sub declaration in eval compiles');

my $call_error;
{
    local $@;
    eval { LexicalSubNestedEval::run_inner_eval() };
    $call_error = $@;
}
is($call_error, '', 'lexical sub can run an eval after declaration');
