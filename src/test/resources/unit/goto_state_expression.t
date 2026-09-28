use strict;
use warnings;
use feature 'state';
use Test::More;

my @seen;
for my $candidate (qw(alpha beta)) {
    goto state $target = $candidate;
    alpha: push @seen, 'alpha'; next;
    beta:  push @seen, 'beta'; next;
}

is_deeply \@seen, [qw(alpha alpha)],
    'computed goto accepts a state declaration expression';
done_testing;
