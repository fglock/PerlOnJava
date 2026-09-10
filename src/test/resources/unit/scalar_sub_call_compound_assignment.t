use strict;
use warnings;
use Test::More tests => 3;

sub increment_by {
    my ($value) = @_;
    return $value;
}

my $total = 0;
$total += increment_by(2) for 1 .. 100;
is($total, 200, 'compound assignment scalarizes repeated subroutine results');

my $method = bless {}, 'ScalarCallResult';
sub ScalarCallResult::value {
    return 3;
}
$total += $method->value for 1 .. 100;
is($total, 500, 'compound assignment scalarizes repeated method results');

is(increment_by(0), 0, 'scalar subroutine result preserves false values');
