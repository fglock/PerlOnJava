use strict;
use warnings;
use Test::More tests => 2;

for my $case (
    [ q{qr/(?@)/}, 'Sequence (?@...) not implemented' ],
    [ q{qr/(?;x/}, 'Sequence (?;...) not recognized' ],
) {
    my ($expression, $expected) = @$case;
    my $compiled = eval "$expression; 1";
    ok(!$compiled && index($@, $expected) >= 0, $expected);
}
