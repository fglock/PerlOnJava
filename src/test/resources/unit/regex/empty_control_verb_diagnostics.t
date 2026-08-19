use strict;
use warnings;
use Test::More tests => 4;

for my $pattern ('(*)', '(*)b') {
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok, "$pattern is rejected");
    ok(index($@, "Unknown verb pattern ''") >= 0,
        "$pattern reports an empty verb pattern");
}
