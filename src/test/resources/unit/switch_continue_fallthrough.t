use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my $result = do {
    given (1) {
        when (/\d/) {
            continue;
            'unreachable';
        }
        default { 'fallback' }
    }
};
is $result, 'fallback', 'continue falls through despite trailing unreachable code';

my $default_result = do {
    given (0) {
        default { 'fallback' }
        'unreachable';
    }
};
is $default_result, 'fallback', 'default exits the given block with its value';

done_testing;
