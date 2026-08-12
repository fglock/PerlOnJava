use strict;
use warnings;
use charnames qw(:full);
use Test::More;

my $punctuation = "\x{ff08}.";
like($punctuation, qr/[\N{FULLWIDTH LEFT PARENTHESIS}]./,
    'named character remains escaped inside a character class');

my $spaces = qr/[\N{U+200D}\N{U+2000}]/;
like("\x{2000}", $spaces, 'named space matches inside a character class');
like("\x{200d}", $spaces, 'named joiner matches inside a character class');
unlike('x', $spaces, 'escape syntax is not treated as class members');

done_testing;
