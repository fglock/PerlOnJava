use strict;
use warnings;
use Test::More;

eval q{ &{+undef}; 1 };
like($@, qr/Can't use an undefined value as a subroutine reference/,
    'calling an undefined code reference reports Perl\'s undef diagnostic');

done_testing;
