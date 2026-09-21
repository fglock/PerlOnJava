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

{
    sub empty_lvalue_return :lvalue { }

    eval 'empty_lvalue_return() = "replacement"';
    like($@, qr/^Can't return undef from lvalue subroutine/,
        'empty lvalue return is rejected in scalar assignment');
}

{
    sub undef_lvalue_return :lvalue { undef }
    sub literal_lvalue_return :lvalue { 'literal' }

    eval 'undef_lvalue_return() = "replacement"';
    like($@, qr/^Can't return undef from lvalue subroutine/,
        'undef lvalue return is rejected in scalar assignment');

    eval 'literal_lvalue_return() = "replacement"';
    like($@, qr/^Can't return a readonly value from lvalue subroutine/,
        'literal lvalue return is rejected');
}

done_testing;
