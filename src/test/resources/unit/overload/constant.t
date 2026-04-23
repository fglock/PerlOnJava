#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;
use overload;

# Regression tests for overload::constant dispatch.
# When a pragma installs a handler in %^H (integer/float/binary), every
# numeric literal emitted within that lexical scope must be rewritten
# at compile time into a call to that handler.
#
# NOTE: any numeric literals inside the scope that has the handler
# installed are themselves subject to the rewrite — including the
# `plan tests => N` count. Tests that need to examine the handler's
# effect are therefore written inside a `{ BEGIN { ... } ... }` block
# and wrapped with is(...) at the outer (handler-free) scope.

our @INT_CALLS;
our @FLOAT_CALLS;
our @BIN_CALLS;

# integer handler
{
    BEGIN { $^H{integer} = sub { push @main::INT_CALLS, [@_]; "I($_[0])" } }
    ::is((my $a = 5), "I(5)",  'literal 5 routed through integer handler');
    ::is((my $b = 42), "I(42)", 'literal 42 routed through integer handler');
}
is_deeply($INT_CALLS[0], ["5", 5, "integer"],
    'handler receives (text, num, category)');
is(scalar @INT_CALLS, 2, 'one call per literal');

# float handler
{
    BEGIN { $^H{float} = sub { push @main::FLOAT_CALLS, [@_]; "F($_[0])" } }
    ::is((my $pi = 3.14), "F(3.14)", 'literal 3.14 routed through float handler');
    ::is((my $e  = 2.71), "F(2.71)", 'literal 2.71 routed through float handler');
}

# binary handler
{
    BEGIN { $^H{binary} = sub { push @main::BIN_CALLS, [@_]; "B($_[0])" } }
    ::is((my $h = 0x10),  "B(0x10)",  'hex literal -> binary handler');
    ::is((my $o = 017),   "B(017)",   'octal literal -> binary handler');
    ::is((my $b = 0b101), "B(0b101)", 'binary literal -> binary handler');
}

# Lexical scoping: handler is active only inside its scope
my $outer = 5;
is($outer, 5, 'plain literal before handler scope');
{
    BEGIN { $^H{integer} = sub { "SCOPED($_[0])" } }
    ::is((my $inner = 7), "SCOPED(7)", 'handler active inside block');
}
my $after = 99;
is($after, 99, 'handler unwound on scope exit');

# Oversize hex literal goes straight to the handler — without
# overload::constant support this would be a parse error.
{
    BEGIN { $^H{binary} = sub { "OVER($_[0])" } }
    ::is((my $big = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFF),
       "OVER(0xFFFFFFFFFFFFFFFFFFFFFFFFFFFF)",
       'oversize hex literal goes through binary handler');
}

# End-to-end smoke test: `use bigint` must now promote literals.
{
    use bigint;
    ::isa_ok((my $x = 5), 'Math::BigInt', 'literal under use bigint');
    ::isa_ok((my $y = 2 ** 200), 'Math::BigInt', '2 ** 200 stays exact');
    ::is("$y", '1606938044258990275541962092341162602522202993782792835301376',
        '2 ** 200 exact value');
}

done_testing();
