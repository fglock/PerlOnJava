use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my $scalar = do {
    given (1) {
        when (1) { 3, 4, 5 }
    }
};
is $scalar, 5, 'given propagates scalar context to a matching when';

my @when_list = do {
    given (1) {
        when (1) { 3, 4, 5 }
    }
};
is_deeply \@when_list, [3, 4, 5], 'given propagates list context to a matching when';

my @default_list = do {
    given (0) {
        default { 6, 7 }
    }
};
is_deeply \@default_list, [6, 7], 'given propagates list context to default';

done_testing;
