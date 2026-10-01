use strict;
use warnings;
use Test::More tests => 2;

eval { my @sorted = sort { last } (2, 1) };
like(
    $@,
    qr/Can't "last" outside a loop block/,
    'last cannot escape a sort comparator as goto',
);

eval { my @sorted = sort { last MISSING } (2, 1) };
like(
    $@,
    qr/Label not found for "last MISSING"/,
    'labeled last keeps the missing-loop-label diagnostic in sort',
);

done_testing;
