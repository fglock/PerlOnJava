use strict;
use warnings;
use feature 'current_sub';
use Test::More;

sub sorted_from_named_sub {
    my $seen;
    my @sorted = sort {
        $seen = CORE::__SUB__;
        $a cmp $b;
    } qw(b a);
    return ($seen, \@sorted);
}

my ($seen, $sorted) = sorted_from_named_sub();
is($seen, \&sorted_from_named_sub,
    'CORE::__SUB__ in a sort block resolves to the enclosing named sub');
is_deeply($sorted, [qw(a b)], 'sort comparator still returns its comparison result');

done_testing();
