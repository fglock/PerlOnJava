use strict;
use warnings;
use Test::More;
use B ();

for my $case (
    [ 0x100,   q{"\x{100}"} ],
    [ 0x436,   q{"\x{436}"} ],
    [ 0x1f600, q{"\x{1f600}"} ],
) {
    my ($ord, $expected) = @$case;
    my $quoted = B::perlstring(chr($ord));

    is($quoted, $expected, sprintf('perlstring ASCII-escapes U+%04X', $ord));
    is(eval($quoted), chr($ord), sprintf('perlstring round-trips U+%04X', $ord));
}

done_testing();
