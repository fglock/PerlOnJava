use strict;
use warnings;
use Test::More;

{
    package SortTailcallDispatch;
    our $AUTOLOAD;

    sub AUTOLOAD {
        $AUTOLOAD =~ s/::cmp\z/::compare/;
        goto &$AUTOLOAD;
    }

    sub compare {
        return $_[0]->{value} <=> $_[1]->{value};
    }

    sub compare_via_helper {
        goto &compare;
    }

    sub DESTROY { }
}

my @values = map { bless { value => $_ }, 'SortTailcallDispatch' } (3, 1, 2);
my @sorted = sort { $a->cmp($b) } @values;
is_deeply([map { $_->{value} } @sorted], [1, 2, 3],
    'AUTOLOAD tail call from a helper sub is allowed during sort');

my @sorted_via_helper = sort {
    SortTailcallDispatch::compare_via_helper($a, $b);
} @values;
is_deeply([map { $_->{value} } @sorted_via_helper], [1, 2, 3],
    'named helper tail call is allowed during sort');

done_testing;
