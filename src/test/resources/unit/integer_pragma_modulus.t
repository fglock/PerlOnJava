use strict;
use warnings;
use Test::More tests => 2;

{
    use integer;
    my $x = length('abc') % -10;
    my $y = (3 / -10) * -10;
    is($x + $y, 3, 'integer modulus and division preserve their signed results');
    is($y, 0, 'integer division truncates a negative fraction toward zero');
}
