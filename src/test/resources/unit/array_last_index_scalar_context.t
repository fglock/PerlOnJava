use strict;
use warnings;
use Test::More;

sub row {
    my @values = qw(alpha beta);
    return \@values;
}

my $values = row();
is($#{$values} + 1, 2, 'array reference last index is numeric in arithmetic');
is_deeply([($#{$values} + 1) .. 1], [], 'a descending range from the last index is empty');

done_testing;
