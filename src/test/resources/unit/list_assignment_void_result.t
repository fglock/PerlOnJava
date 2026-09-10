use strict;
use warnings;
use Test::More tests => 3;

sub unpack_in_void_context {
    my ($left, $right) = @_;
    return (defined $left ? $left : '<undef>') . ':'
        . (defined $right ? $right : '<undef>');
}

is(unpack_in_void_context('first', 'second'), 'first:second',
    'parameter unpacking assigns both values');
is(unpack_in_void_context('left'), 'left:<undef>',
    'parameter unpacking assigns undef for a missing value');
is(unpack_in_void_context(0, 0), '0:0',
    'parameter unpacking retains false values');
