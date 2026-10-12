use strict;
use warnings;
use Test::More;
use B qw(svref_2object SVp_IOK SVp_NOK);

sub looks_like_integer {
    my $flags = svref_2object(\$_[0])->FLAGS;
    return ($flags & SVp_IOK) && !($flags & SVp_NOK);
}

sub looks_like_even {
    return looks_like_integer($_[0]) && $_[0] % 2 == 0;
}

for my $value (4**4**4, -(4**4**4)) {
    my $flags = svref_2object(\$value)->FLAGS;
    ok(!($flags & SVp_IOK), 'large finite float does not have the integer flag');
    ok($flags & SVp_NOK, 'large finite float has the numeric float flag');
    ok(!looks_like_integer($value), 'large finite float is not classified as an integer');
    ok(!looks_like_even($value), 'large finite float is not classified as even');
}

done_testing();
