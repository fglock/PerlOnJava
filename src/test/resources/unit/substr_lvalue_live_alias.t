use strict;
use warnings;
use Test::More tests => 4;

my $x = '1234';
for (substr($x, 1, 2)) {
    $_ = 'a';
    is($x, '1a4', 'first assignment replaces original extent');
    $_ = 'xyz';
    is($x, '1xyz4', 'later assignment replaces prior replacement extent');
    $x = '56789';
    $_ = 'pq';
    is($x, '5pq9', 'alias remains live after parent scalar replacement');
}

$x = '1234';
for (substr($x, -3, 2)) {
    $_ = 'a';
    $x = 'abcdefg';
    is($_, 'f', 'negative offset is reevaluated against current parent length');
}
