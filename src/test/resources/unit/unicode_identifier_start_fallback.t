use strict;
use warnings;
use utf8;
use Test::More;

my $Ⅻ = 'roman numeral';

is $Ⅻ, 'roman numeral', 'a Unicode Letter_Number can start a lexical variable';

done_testing;
