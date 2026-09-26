use strict;
use warnings;
use feature qw(refaliasing state);
no warnings 'experimental::refaliasing';
use Test::More;

for (1, 2) {
    \my @x = [1 .. 3],
    \my(@y) = \3,
    \state @a = [1 .. 3],
    \state(@b) = \3 if $_ == 1;
    \state @c = [$_];
    if ($_ == 2) {
        die 'lexical x did not clear' unless @x == 0;
        die 'lexical y did not clear' unless @y == 0;
        die 'state a did not persist' unless "@a" eq '1 2 3';
        die 'state b did not persist' unless "@b" eq '3';
        die 'state c did not persist' unless $c[0] == 1;
    }
}

pass 'mixed refalias loop checks completed';
done_testing;
