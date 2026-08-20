use strict;
use warnings;
use Test::More tests => 3;

no warnings 'experimental::regex_sets';

for my $case (
    [ q{qr/(?[ \cK \t ])/}, 'Operand with no preceding operator' ],
    [ q{qr/(?[ \cK ( \t ) ])/}, q{Unexpected '(' with no preceding operator} ],
    [ q{qr/(?[ \cK + ) ])/}, q{Unexpected ')'} ],
) {
    my ($expression, $expected) = @$case;
    my $compiled = eval "$expression; 1";
    ok(!$compiled && index($@, $expected) >= 0, $expected);
}
