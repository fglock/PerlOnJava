use strict;
use warnings;
use Test::More tests => 4;

for my $case (
    [q{qr/(?'/}, q{Sequence (?'... not terminated}],
    [q{qr/(?<name)/}, q{Sequence (?<... not terminated}],
    [q{qr/(?(</}, q{Sequence (?(<... not terminated}],
    [q{qr/(?('/}, q{Sequence (?('... not terminated}],
) {
    my $compiled = eval "$case->[0]; 1";
    ok(!$compiled && index($@, $case->[1]) >= 0,
       'incomplete named delimiter uses the Perl diagnostic');
}
