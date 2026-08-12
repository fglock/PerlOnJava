use strict;
use warnings;
use Test::More tests => 2;

my %values;
my $old = eval q{ @values{qw(first second)}++ };

is($old, 0, 'postincrement returns numeric zero for the old undef slice value');
is_deeply(\%values, { first => undef, second => 1 },
    'postincrement scalarizes a hash slice and increments its final element');
