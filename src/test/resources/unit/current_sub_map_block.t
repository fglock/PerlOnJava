use v5.26;
use strict;
use warnings;
use Test::More tests => 3;

sub plain_values {
    my ($value) = @_;
    return $value unless ref $value;
    return [map { __SUB__->($_) } @$value] if ref($value) eq 'ARRAY';
    return {map { $_ => __SUB__->($value->{$_}) } keys %$value};
}

is_deeply plain_values([1, [2, 3]]), [1, [2, 3]],
    '__SUB__ in map array block refers to enclosing sub';
is_deeply plain_values({a => {b => 4}}), {a => {b => 4}},
    '__SUB__ in map hash block refers to enclosing sub';
is plain_values('leaf'), 'leaf', 'recursive base case is preserved';
