use strict;
use warnings;
use Test::More;

our %target;
our @remaining_counts;

{
    package Local::UndefHashOwnerIndex;

    sub DESTROY {
        push @main::remaining_counts, scalar keys %main::target;
    }
}

$target{late_bless} = {};
$target{early_one} = bless {}, 'Local::UndefHashOwnerIndex';
$target{early_two} = bless {}, 'Local::UndefHashOwnerIndex';
bless $target{late_bless}, 'Local::UndefHashOwnerIndex';

undef %target;

is_deeply(
    [sort { $a <=> $b } @remaining_counts],
    [0, 1, 2],
    'undef %hash runs each destructor against the progressively shrinking hash',
);

done_testing();
