use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my $result = do {
    given (2) {
        my $matched;
        when (2) { $matched = 'first'; continue }
        when (2) { "$matched second" }
        default { 'default' }
    }
};

is($result, 'first second',
    'continue in when falls through to the next matching clause');

done_testing;
