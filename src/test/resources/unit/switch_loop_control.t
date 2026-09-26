use strict;
use warnings;
use feature 'switch';
no warnings 'experimental::smartmatch';
use Test::More;

my @last_seen;
for my $value ('a' .. 'e') {
    given ($value) {
        push @last_seen, $value;
        when ('b') { last }
    }
}
is_deeply \@last_seen, ['a', 'b'], 'last in when exits the enclosing loop';

my @next_seen;
for my $value ('a' .. 'e') {
    given ($value) {
        when (/b|d/) { next }
        push @next_seen, $value;
    }
}
is_deeply \@next_seen, ['a', 'c', 'e'], 'next in when advances the enclosing loop';

my $break_result = do {
    given (1) {
        break when 1;
        'unreachable';
    }
};
is $break_result, undef, 'break exits given without a value';

done_testing;
