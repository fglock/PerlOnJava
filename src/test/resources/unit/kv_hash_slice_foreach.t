use strict;
use warnings;
use Test::More tests => 2;

my %hash = (a => 1, b => 2, c => 3);
$_++ for %hash{'b', 'c'};

is_deeply(\%hash, {a => 1, b => 3, c => 4},
    'foreach aliases the values of a key/value hash slice');
is(join('|', %hash{'b', 'c'}), 'b|3|c|4',
    'ordinary key/value slice still returns alternating keys and values');
