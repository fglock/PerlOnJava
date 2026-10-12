use strict;
use warnings;
use Test::More;

my @items = grep {
    0 ? $_ : do {
            SKIP: { skip 'failed require', 1 };
            ()
        }
} (1);

is scalar(@items), 0, 'failed module omitted';

done_testing;
