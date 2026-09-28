use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my @result;
for (0, 1, 2, 3) {
    my @value = do {
        given ($_) {
            continue when $_ <= 1;
            break when 1;
            next when 2;
            6, 7;
        }
    };
    push @result, "$_:@value";
}

is_deeply \@result, ['0:6 7', '1:', '3:6 7'],
    'postfix continue falls through without advancing the topicalizer loop';

done_testing;
