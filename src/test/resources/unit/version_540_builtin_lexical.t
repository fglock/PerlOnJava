use v5.40;
use Test::More;

is reftype([]), 'ARRAY', 'the v5.40 bundle imports reftype lexically';
is true(), 1, 'the v5.40 bundle imports true lexically';

sub nested_eval {
    return sub { eval 'reftype([])' or die $@ };
}

is nested_eval()->(), 'ARRAY', 'a nested string eval retains the lexical reftype import';

done_testing;
