use strict;
use warnings;
use Test::More tests => 3;

# Direct string test
my $result1 = "abc\l\Udefgh\Ei";
is($result1, 'abcdEFGHi', 'Literal: abc\\l\\Udefgh\\Ei should become abcdEFGHi');

# Variable interpolation test
my $v = "defg";
my $result2 = "abc\l\U${v}h\Ei";
is($result2, 'abcdEFGHi', 'Interpolated: abc\\l\\U\${v}h\\Ei should become abcdEFGHi');

$v = "";
$result2 = "abc\l\U${v}h\Ei";
is($result2, 'abchi', 'Interpolated: abc\\l\\U\${v}h\\Ei should become abchi');

$v = "";
$result2 = "abc\L lower \U${v} upper \Ei";
is($result2, 'abc lower  UPPER i', 'Interpolated \\L + \\U');
