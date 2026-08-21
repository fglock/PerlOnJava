use strict;
use warnings;
use utf8;
use Test::More;

for my $pattern ('ネ(?[[[:ネ: ])ネ', 'ï(?[[[:ï: ])ï') {
    my $compiled = eval { qr/$pattern/ };
    ok(!$compiled, "$pattern is rejected");
    like($@, qr/^Syntax error in \(\?\[\.\.\.\]\) in regex;/,
        "$pattern reports extended-class syntax");
    like($@, qr/\Q$pattern\E <-- HERE /,
        "$pattern marks the end of the malformed outer class");
}

done_testing;
