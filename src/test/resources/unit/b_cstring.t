use strict;
use warnings;
use utf8;
use B ();
use Test::More;

is(B::cstring(qr/foo/i), '"(?^i:foo)"', 'quotes a regexp with its modifiers');
is(B::cstring("a\n\0\""), '"a\\n\\000\\""',
    'escapes control characters, NUL, and quotes');
is(B::cstring('Υ'), '"\\316\\245"', 'encodes wide characters as UTF-8 octets');

done_testing;
