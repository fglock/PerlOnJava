use strict;
use warnings;
use Test::More;

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

is_deeply(
    [ abs2rel(qw(1 2 3)) ],
    [ qw(1 1 1) ],
    'subtraction preserves string-compatible scalar values',
);

is_deeply(
    [ (rel2abs(qw(1 1 1)))[1 .. 2] ],
    [ qw(2 3) ],
    'addition preserves string-compatible computed values',
);

done_testing;
