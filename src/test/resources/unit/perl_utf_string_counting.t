use strict;
use warnings;
use utf8;
use Test::More;

is(length('PerlOnJava'), 10, 'ASCII length');
is(length("A\x{1F600}B"), 3, 'supplementary scalar has one Perl character');
is(substr("A\x{1F600}B", 1, 1), "\x{1F600}",
   'substr keeps supplementary scalar whole');
is(length("A\x{FFFD}B"), 3, 'replacement character is ordinary text');

done_testing;
