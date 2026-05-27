use strict;
use warnings;
use Test::More;

is("@^", '@^', 'bare @^ remains literal in double-quoted strings');
is("@^foo", '@^foo', 'bare @^name remains literal in double-quoted strings');

my @array = qw(one two);
is("@array", 'one two', 'ordinary arrays still interpolate');

my $safe_char = qr|[^\w!%+,\-./:=@^]|;
ok('x' !~ $safe_char, 'regex char class containing @^ parses under strict');
ok('*' =~ $safe_char, 'regex char class containing @^ still matches unsafe chars');

done_testing();
