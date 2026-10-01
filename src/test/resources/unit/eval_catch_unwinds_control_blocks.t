use strict;
use warnings;
use Test::More tests => 4;

our $eval_unwind_visits;
++ $eval_unwind_visits;
die "caught eval replayed its enclosing source\n" if $eval_unwind_visits > 1;

eval { { die "nested block failure\n"; } };
like $@, qr/nested block failure/, 'eval catches failure inside a bare block';

eval 'next';
like $@, qr/Can't "next" outside a loop block/,
    'later next does not target a block abandoned by the caught failure';
is $eval_unwind_visits, 1, 'the source executes only once';

my $iterations = 0;
while ($iterations < 2) {
    ++$iterations;
    eval { { die "inner failure\n"; } };
    eval 'next';
    die "next did not reach the enclosing loop\n";
}
is $iterations, 2, 'catch preserves the loop outside eval';
