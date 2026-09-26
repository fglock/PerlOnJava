use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my $postfix_value = do {
    given (1) {
        3, 4, 5 when 1;
        'unreachable';
    }
};
is $postfix_value, 5, 'matching postfix when exits the given expression';

my $continued = do {
    my $value = 0;
    given ('apple') {
        $value = 1, continue when $_ eq 'apple';
        $value += 2;
        $value;
    }
};
is $continued, 3, 'postfix continue falls through instead of exiting given';

my $nested_continue = do {
    my $value = 0;
    given ('pear') {
        do { $value = 1; continue; 'unreachable' } when /pea/;
        $value += 2;
        $value;
    }
};
is $nested_continue, 3, 'postfix nested continue discards unreachable code and falls through';

done_testing;
