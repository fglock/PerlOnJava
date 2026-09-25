use v5.22;
use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Test::More;

my ($left, $right) = (1, 2);

my $choose_left = 1;
$choose_left ? \$left : $right = \3;
is $left, 3, 'conditional target aliases an explicitly referenced true branch';
is $right, 2, 'conditional target leaves the unselected false branch alone';

$choose_left = 0;
$choose_left ? \$left : \$right = \4;
is $right, 4, 'conditional target aliases an explicitly referenced false branch';

my $choose_nested = 1;
\($choose_nested ? $choose_left ? $left : $right : $right) = \5;
is $right, 5, 'nested conditional target binds the selected scalar slot';

done_testing;
