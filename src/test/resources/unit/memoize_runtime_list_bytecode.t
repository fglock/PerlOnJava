use strict;
use warnings;
use Test::More;
use Memoize;

my $calls = 0;
sub doubled {
    $calls++;
    return $_[0] * 2;
}

my $memoized = memoize(\&doubled, INSTALL => undef);

is($memoized->(21), 42, 'memoized closure returns its value');
is($memoized->(21), 42, 'memoized closure returns its cached value');
is($calls, 1, 'memoized wrapper called the original once');

done_testing;
