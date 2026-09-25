use strict;
use warnings;
use Test::More tests => 1;

my @values;
$values[1] = 1;
foreach my $value ((), @values) {
    $value = 5;
}

is join('', @values), '55',
   'foreach lvalue lists preserve array holes while aliasing their cells';
