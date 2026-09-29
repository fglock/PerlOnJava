use strict;
use warnings;
use Test::More;

my $error = eval q{
    $^ ^= *: = ** = *^= *: = ** = *^= *: = ** = *:;
    1;
};

is($@, '', 'a typeglob special variable *^ is distinct from the ^= operator');

done_testing;
