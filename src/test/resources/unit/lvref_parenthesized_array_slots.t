use v5.22;
use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

our ($first, $second) = (10, 20);
our @aliases;

\(@aliases) = (\$first, \$second);

is_deeply \@aliases, [10, 20], 'parenthesized array target receives scalar referents';
$first = 11;
is $aliases[0], 11, 'parenthesized array target aliases its first scalar slot';
$aliases[1] = 21;
is $second, 21, 'parenthesized array target aliases its second scalar slot';

done_testing;
