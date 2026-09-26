use v5.22;
use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

our ($scalar, $first, $second) = (0, 1, 2);
our (@whole, @slots);

\($scalar, @whole, (@slots)) = (\$first, \@ARGV, \$first, \$second);

is $scalar, 1, 'scalar target consumes its scalar reference';
is \@whole, \@ARGV, 'unparenthesized aggregate target consumes one aggregate reference';
is_deeply \@slots, [1, 2], 'parenthesized aggregate target consumes remaining scalar references';
$first = 3;
is $slots[0], 3, 'parenthesized aggregate slots retain aliases';

done_testing;
