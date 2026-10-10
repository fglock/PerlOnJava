use strict;
use warnings;
use Test::More tests => 5;

{
    use integer;

    is(3 % -10, 3, 'positive dividend keeps its sign with negative divisor');
    is(-3 % 10, -3, 'negative dividend keeps its sign with positive divisor');
    is(-3 % -10, -3, 'negative dividend keeps its sign with negative divisor');
    is(3 % 10, 3, 'positive dividend keeps its sign with positive divisor');

    my $x = length('abc') % -10;
    my $y = (3 / -10) * -10;
    is($x + $y, 3, 'use integer mixed remainder and division expression');
}
