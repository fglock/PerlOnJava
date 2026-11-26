use strict;
use warnings;
use Test::More;

my @closures;
push @closures, sub { eval '1' } for 1, 2;

isnt(
    $closures[0],
    $closures[1],
    'each evaluation of an anonymous sub containing eval creates a distinct CV',
);

done_testing();
