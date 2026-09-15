use strict;
use warnings;
use Test::More;
use PadWalker qw(set_closed_over);

my $left = 10;
my $right = 20;
my $sum = sub { $left + $right };

is($sum->(), 30, 'captured integer closure starts with original cells');

my $replacement = 40;
set_closed_over($sum, { '$left' => \$replacement });
is($sum->(), 60, 'closure reads the PadWalker replacement cell');

$replacement = 70;
is($sum->(), 90, 'closure continues to observe the replacement cell');

done_testing;
