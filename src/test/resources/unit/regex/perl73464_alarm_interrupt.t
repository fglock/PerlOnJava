use strict;
use warnings;
use Test::More tests => 1;

local $SIG{ALRM} = sub {
    pass('alarm interrupts Perl 73464 pathological backtracking');
    exit 0;
};

alarm 1;
$_ = 'a' x 1000 . 'b' x 1000 . 'c' x 1000;
/.*a.*b.*c.*[de]/;
alarm 0;
fail('pathological match returned before the alarm');
