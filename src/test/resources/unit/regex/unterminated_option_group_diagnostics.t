use strict;
use warnings;
use Test::More tests => 6;

for my $pattern ('(?i', '(?a-x', '(?-') {
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok, "$pattern is rejected");
    ok(index($@, 'Sequence (?... not terminated') >= 0,
        "$pattern reports an unterminated option sequence");
}
