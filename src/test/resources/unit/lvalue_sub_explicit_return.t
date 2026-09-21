use strict;
use warnings;
use Test::More;

{
    my $slot = 'before';
    sub loop_return_lvalue :lvalue {
        for (1, 2) {
            return $slot;
        }
    }

    loop_return_lvalue() = 'after';
    is($slot, 'after', 'explicit return from a loop preserves an lvalue alias');
}

done_testing;
