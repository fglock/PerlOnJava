use strict;
use warnings;
use Test::More tests => 4;

# Copying through a reference to $#array reads the array's current last
# index, not the value at the time the reference was taken.

my @array = (1);
my $last_index = \$#array;
push @array, 2, 3;

my $copy = $$last_index;
is($copy, 2, 'scalar copy sees elements pushed after the reference was taken');

my ($list_copy) = ($$last_index);
is($list_copy, 2, 'list copy sees the current last index');

$#array = 0;
$copy = $$last_index;
is($copy, 0, 'scalar copy sees a shortened array');

my $freed = \$#{ [ 1, 2 ] };
$copy = $$freed;
ok(!defined $copy, 'copy of $# for a freed anonymous array is undef');
