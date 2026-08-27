use strict;
use warnings;
use Test::More;
use Test::Differences;

sub abs2rel {
    return if !@_;
    my @result = $_[0];
    for my $i (1 .. $#_) {
        push @result, $_[$i] - $_[$i - 1];
    }
    return @result;
}

sub rel2abs {
    return if !@_;
    my @result = $_[0];
    for my $i (1 .. $#_) {
        push @result, $result[-1] + $_[$i];
    }
    return @result;
}

eq_or_diff(
    [ abs2rel(qw(1 2 3)) ],
    [ qw(1 1 1) ],
    'subtraction preserves string-compatible scalar values',
);

eq_or_diff(
    [ rel2abs(qw(1 1 1)) ],
    [ qw(1 2 3) ],
    'addition preserves string-compatible scalar values',
);

done_testing;
