use strict;
use warnings;
use utf8;
use Test::More;

eval q!sub ネ (\%) {} ネ(1);!;
like($@, qr/Type of arg 1 to main::ネ must be hash/u,
    'bad prototype argument diagnostic preserves UTF-8 subroutine names');

done_testing();
