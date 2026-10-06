use strict;
use warnings;
use Test::More;

sub goto_from_nested_conditional_to_elsif {
    my ($outer, $inner, $alternate) = @_;
    if ($outer) {
        if ($inner) {
            goto TARGET;
        }
    } elsif ($alternate) {
        TARGET: return 'target reached';
    }
    return 'fell through';
}
is(goto_from_nested_conditional_to_elsif(1, 1, 0), 'target reached',
    'goto from nested conditional can reach an elsif label');

done_testing();
