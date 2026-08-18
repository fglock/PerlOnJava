use strict;
use warnings;
use Test::More tests => 16;

for my $pair (
    [ True => 1 ], [ False => 0 ],
    [ Yes  => 1 ], [ No    => 0 ],
    [ Y    => 1 ], [ N     => 0 ],
    [ T    => 1 ], [ F     => 0 ],
) {
    my ($value, $expected) = @$pair;
    my $property = "ASCII_Hex_Digit=$value";
    my $positive = eval qq{'A' =~ /\\p{$property}/};
    is($@, '', "$property compiles");
    is(!!$positive, !!$expected, "$property has Perl boolean semantics");
}
