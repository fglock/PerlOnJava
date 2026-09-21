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

{
    our @aggregate_lvalue_return = (2, 3);
    sub aggregate_lvalue_return :lvalue { @aggregate_lvalue_return }

    is(aggregate_lvalue_return->${\sub { return $_[0] }}, 2,
        'aggregate lvalue return observes scalar context through a dereference');
}

{
    our %keys_lvalue_return = (item => 'value');
    sub keys_lvalue_return :lvalue { keys %keys_lvalue_return }

    eval '(keys_lvalue_return()) = 64';
    like($@, qr/^Can't modify keys in list assignment/,
        'keys lvalue return rejects list assignment');
}

done_testing;
