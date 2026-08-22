use strict;
use warnings;
use utf8;
use Test::More;

like('abc', qr/^\N{3}$/, '\\N{3} is an exact quantifier for plain non-newline');
unlike('ab', qr/^\N{3}$/, '\\N{3} rejects a shorter string');
unlike("ab\n", qr/^\N{3}$/, '\\N{3} still excludes line feed');

like('abcde', qr/^\N{5,}$/, '\\N{5,} accepts its lower bound');
like('abcdefg', qr/^\N{5,}$/, '\\N{5,} remains open above its lower bound');
unlike('abcd', qr/^\N{5,}$/, '\\N{5,} rejects a shorter string');

like('A', qr/^\N{LATIN CAPITAL LETTER A}$/,
    'a nonnumeric braced name remains a named-character escape');
unlike('AAA', qr/^\N{LATIN CAPITAL LETTER A}$/,
    'a named-character escape is not parsed as a quantifier');

done_testing;
