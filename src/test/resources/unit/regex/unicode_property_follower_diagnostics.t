use strict;
use warnings;
use Test::More tests => 7;

for my $case (
    [ '\\p A', 'p' ],
    [ '\\P:',  'P' ],
    [ '\\p^',  'p' ],
) {
    my ($pattern, $escape) = @$case;
    my $ok = eval "qr/$pattern/; 1";
    ok(!$ok, "$pattern is rejected");
    my $expected = "Character following \\$escape must be '{' or a single-character Unicode property name";
    ok(index($@, $expected) >= 0,
        "$pattern reports its invalid property follower");
}

my $ok = eval 'qr/\\p/; 1';
ok(!$ok && $@ =~ /Empty \\p/, 'bare property escape retains its empty diagnostic');
