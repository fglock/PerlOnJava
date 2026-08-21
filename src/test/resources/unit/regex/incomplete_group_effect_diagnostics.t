use strict;
use warnings;
use Test::More tests => 4;

for my $pattern ('(?', 'a(?') {
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok, "$pattern is rejected");
    ok(index($@, 'Sequence (? incomplete') >= 0,
        "$pattern reports an incomplete group effect");
}
