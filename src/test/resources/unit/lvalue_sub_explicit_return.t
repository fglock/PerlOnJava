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

{
    our @lvalue_array_return = qw(one two three);
    sub lvalue_array_return :lvalue { @lvalue_array_return }
    sub replace_last { $_[2] = 'free' }
    replace_last(lvalue_array_return());
    is(join(' ', @lvalue_array_return), 'one two free',
        'lvalue array return preserves argument aliases');
}

{
    our ($lvalue_prefix_return, @lvalue_mixed_return) = ('half', qw(one two three));
    sub lvalue_mixed_return :lvalue { $lvalue_prefix_return, @lvalue_mixed_return }
    sub replace_mixed { $_[2] = 'free' }
    replace_mixed(lvalue_mixed_return());
    is(join(' ', @lvalue_mixed_return), 'one free three',
        'mixed lvalue return preserves array argument aliases');
}

{
    our $indexed_lvalue_return = 'before';
    sub indexed_lvalue_sub :lvalue { $indexed_lvalue_return }
    sub indexed_lvalue_sub_return :lvalue { return $indexed_lvalue_return }

    ${\(indexed_lvalue_sub())[0]} = 'implicit';
    is($indexed_lvalue_return, 'implicit', 'indexed implicit lvalue return preserves its alias');

    ${\(indexed_lvalue_sub_return())[0]} = 'explicit';
    is($indexed_lvalue_return, 'explicit', 'indexed explicit lvalue return preserves its alias');
}

{
    eval { +sub :lvalue { return 3 }->() = 4 };
    like($@, qr/^Can't return a readonly value from lvalue subroutine/,
        'anonymous numeric lvalue return is rejected');
}

done_testing;
