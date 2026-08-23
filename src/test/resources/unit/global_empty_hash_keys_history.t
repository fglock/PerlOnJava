use strict;
use warnings;
use Test::More;

use vars qw(%history_big %history_small);

%history_big = (1 .. 50_000);
%history_small = (1 .. 10);

delete @history_big{keys %history_big};
delete @history_small{keys %history_small};

is scalar(keys %history_big), 0,
    'global hash with a large allocation history has no keys';
is scalar(keys %history_small), 0,
    'global hash with a small allocation history has no keys';

my @big_keys = keys %history_big;
my @small_keys = keys %history_small;
is_deeply \@big_keys, [],
    'large emptied global hash returns an empty key list';
is_deeply \@small_keys, [],
    'small emptied global hash returns an empty key list';

for (1 .. 1_000) {
    @big_keys = keys %history_big;
    @small_keys = keys %history_small;
}
is_deeply \@big_keys, [],
    'repeated list-context keys stays empty after a large history';
is_deeply \@small_keys, [],
    'repeated list-context keys stays empty after a small history';

done_testing;
