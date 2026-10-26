use strict;
use warnings;
use Test::More;
use PadWalker qw(set_closed_over);

my ($left, $right) = (20, 30);
my $sum = sub { $left + $right };

is($sum->(), 50, 'captured cells are read on the first call');
$right = 31;
is($sum->(), 51, 'in-place mutation remains visible through cached cells');

my $replacement = 100;
set_closed_over($sum, { '$left' => \$replacement });
is($sum->(), 131, 'rebinding invalidates the cached capture-cell array');
$replacement = 200;
is($sum->(), 231, 'the replacement cell remains live after rebinding');

done_testing;
