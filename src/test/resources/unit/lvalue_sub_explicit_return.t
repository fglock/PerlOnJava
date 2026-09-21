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

{
    our @slot;
    sub nested_block_lvalue :lvalue {
        {
            @slot;
        }
    }

    (nested_block_lvalue(0)) = (1, 2, 3);
    is_deeply(\@slot, [1, 2, 3], 'nested bare block preserves an lvalue list alias');
}

done_testing;
