use strict;
use warnings;
use Test::More tests => 6;

for my $pattern ('x(?#', 'x(?#:', '(?#abc') {
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok, "$pattern is rejected");
    ok(index($@, 'Sequence (?#... not terminated') >= 0,
        "$pattern reports an unterminated comment sequence");
}
