use strict;
use warnings;
use Test::More tests => 7;

sub read_indexed_sum {
    my @copy = @_;
    my $sum = $copy[0] + $copy[1];
    return $sum;
}

sub mutate_copy {
    my @copy = @_;
    $copy[0] = 99;
    return $copy[0];
}

sub retain_copy_reference {
    my @copy = @_;
    return \@copy;
}

sub pass_index_to_callback {
    my ($callback) = shift;
    my @copy = @_;
    $callback->($copy[0]);
    return $copy[0];
}

my ($left, $right) = (4, 7);
is(read_indexed_sum($left, $right), 11,
    'read-only indexed argument copy retains values');
is($left, 4, 'read-only indexed argument copy does not change caller');

is(mutate_copy($left), 99, 'array mutation updates the private copy');
is($left, 4, 'array mutation does not update the caller argument');

my $retained = retain_copy_reference($left);
$retained->[0] = 55;
is($left, 4, 'escaped array reference remains independent of caller argument');

is(pass_index_to_callback(sub { $_[0] = 88 }, $left), 88,
    'callback can modify the private copied scalar');
is($left, 4, 'callback cannot modify the caller argument through the private copy');
