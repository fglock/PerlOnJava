use strict;
use warnings;
use Test::More;

my $deferred;
BEGIN {
    # The property sub is deliberately unavailable while this qr// compiles.
    $deferred = qr/\p{InDeferredKana}/;
}

sub InDeferredKana {
    return "3040\t309F\n30A0\t30FF\n";
}

ok("\x{3040}" =~ $deferred,
   'deferred property resolves after its sub is defined');

for my $case (
    [ '\\p{InDeferredKana}', "\x{3040}", 1 ],
    [ '\\P{InDeferredKana}', "\x{3040}", 0 ],
    [ '\\P{InDeferredKana}', "\x{303F}", 1 ],
    [ '\\p{InDeferredKana}', "\x{303F}", 0 ],
) {
    my ($source, $subject, $expected) = @$case;
    my $pattern = eval "qr/$source/";
    is($subject =~ $pattern ? 1 : 0, $expected,
       "$source remains correct after deferred cache resolution");
}

done_testing();
