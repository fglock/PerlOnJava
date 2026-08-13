use strict;
use warnings;

use Test::More tests => 2;

my %values = (first => 1, second => 2);
my $count = grep { $_ eq 'first' } keys %values;
$count += grep { $_ eq 'second' } keys %values;

is($count, 2, 'compound addition scalarizes a list-producing right operand');

my $empty_count = 7;
$empty_count += grep { 0 } keys %values;
is($empty_count, 7, 'compound addition scalarizes an empty list to undef/zero');
