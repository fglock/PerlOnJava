use strict;
use warnings;

use Scalar::Util qw(refaddr weaken);
use Test::More;

our %WRAP_CACHE;

# Reduced from Test::Deep's cmp_details/descend/wrap lifetime pattern.  Keep
# this test self-contained: perl5/cpan is an ignored developer checkout and is
# intentionally absent from clean CI workspaces.
sub compare_deeply {
    my ($got, $expected) = @_;

    local %WRAP_CACHE;
    my $wrapper = bless { val => $expected }, 'Local::Comparator';
    $WRAP_CACHE{refaddr($expected)} = $wrapper;

    my $stack = [{ exp => $wrapper, got => $got }];
    return ref($got) eq ref($expected) && @$got == @$expected;
}

sub left {
    my ($ref) = @_;
    compare_deeply($ref, []);
    return 'left';
}

sub right {
    my ($ref) = @_;
    compare_deeply([], $ref);
    return 'right';
}

for my $sub (\&left, \&right) {
    my $ref = [];
    my $weak = $ref;
    weaken($weak);
    my $side = $sub->($ref);
    $ref = 1;
    ok(!defined($weak), "$side does not capture the compared reference");
}

done_testing;
