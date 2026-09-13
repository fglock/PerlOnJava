use strict;
use warnings;
use Test::More tests => 5;

# The JVM may discard only the private assignment result in void context.  The
# RHS must still be fully evaluated before the replacement is visible.
my @values = (1, 2, 3);
@values = reverse @values;
is_deeply \@values, [3, 2, 1], 'void array assignment preserves RHS snapshot semantics';

my @events;
sub replacement {
    push @events, 'rhs';
    return (4, 5);
}
@values = replacement();
is_deeply \@events, ['rhs'], 'void assignment evaluates RHS once';
is_deeply \@values, [4, 5], 'void assignment stores all RHS values';

# Outside void context the assignment value remains observable.
my $count = scalar(@values = (6, 7, 8));
is $count, 3, 'scalar assignment result remains the RHS count';
is_deeply \@values, [6, 7, 8], 'scalar-context assignment still updates array';
