use strict;
use warnings;
use Test::More tests => 10;

my $bar_twice = qr/(bar)\g-1/;

like 'barbar', $bar_twice,
    'unbraced negative subpattern call repeats the preceding capture';
unlike 'barbaz', $bar_twice,
    'unbraced negative subpattern call must match the called capture pattern';
like 'foobarbarxyz', qr/foo${bar_twice}xyz/,
    'relative call keeps its lexical target after qr interpolation';
like 'foobarbarxyz', qr/(foo)${bar_twice}xyz/,
    'outer captures do not retarget an interpolated relative call';
like 'foobarbarxyz', qr/(foo${bar_twice})xyz/,
    'nested outer captures do not retarget an interpolated relative call';

like 'aba', qr/\A((a)b)\g-1\z/,
    'nested relative call targets the most recently opened physical group';
unlike 'abab', qr/\A((a)b)\g-1\z/,
    'nested relative call does not target the enclosing capture';

my $nested = qr/((x)y)\g-1/;
like 'xyx', $nested,
    'standalone nested relative call repeats the innermost preceding group';
like 'prefixxyxend', qr/prefix${nested}end/,
    'nested lexical target survives interpolation';
unlike 'prefixxyxyend', qr/prefix${nested}end/,
    'interpolated nested call is not rebound to its enclosing capture';
