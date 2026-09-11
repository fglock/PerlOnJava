use strict;
use warnings;
use utf8;
use Test::More;

is(substr('abcdefghijklmnopqrstuvwxyz', -24), 'cdefghijklmnopqrstuvwxyz',
    'ASCII negative offset retains character semantics');
is(substr("A\x{010A}B\x{0A23}C", 1, 3), "\x{010A}B\x{0A23}",
    'BMP characters each occupy one substring offset');
is(substr("A\x{1F600}BC", 1, 1), "\x{1F600}",
    'supplementary character remains one substring offset');
is(substr("A\x{1F600}BC", 2), 'BC',
    'offset following a supplementary character remains correct');

done_testing;
