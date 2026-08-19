use strict;
use warnings;
use Test::More;

for my $name (qw(gcb sb wb lb)) {
    my $positive = qr/\b{$name}/;
    my $negative = qr/\B{$name}/;
    my $spaced_positive = qr/\b{ $name }/;
    my $spaced_negative = qr/\B{ $name }/;

    unlike('', $positive, "empty text has no $name boundary");
    like('', $negative, "empty text satisfies negated $name boundary");
    unlike('', $spaced_positive, "whitespace is accepted around $name");
    like('', $spaced_negative, "spaced negated $name retains empty semantics");
}

done_testing;
