use strict;
use warnings;
use Test::More tests => 10;

# Regression: `Foo::->bar()` should pass class name "Foo", not "Foo::"
# See dev/modules/ppi.md (RC1).

package Foo;
sub classname { $_[0] }
sub isa_check { $_[0]->isa('Foo') ? 1 : 0 }

package Foo::Bar;
our @ISA = ('Foo');

package main;

is(Foo->classname,       'Foo',      'regular bareword class name');
is(Foo::->classname,     'Foo',      'bareword class with trailing :: is stripped');
is(Foo::Bar::->classname,'Foo::Bar', 'nested bareword class with trailing :: is stripped');

ok(Foo::->isa_check,      'isa still works through trailing-:: invocant');
ok(Foo::Bar::->isa_check, 'isa finds parent class through trailing-:: invocant');

# Regression: `bless $ref, Foo::Bar::;` should strip trailing "::".
# Previously produced ref "Foo::Bar::" (keeping the ::), breaking
# isa/ref checks and ->method() dispatch.
{
    my $obj = bless { k => 1 }, Foo::Bar::;
    is(ref($obj), 'Foo::Bar', 'bless + trailing :: strips the ::');
    ok($obj->isa('Foo::Bar'), '...and isa works');
}

# Regression: `Foo::Bar:: eq $x` was mis-parsed as a sub call consuming
# `eq` as its first argument, producing an "Undefined subroutine" error.
# The word-operators eq/ne/lt/gt/le/ge/cmp/x/isa/and/or/xor are lexed
# as IDENTIFIER tokens but still participate in expressions.
{
    my $x = 'Foo::Bar';
    ok(Foo::Bar:: eq $x, 'package literal followed by eq works');
    ok(Foo::Bar:: ne 'zzz', 'package literal followed by ne works');
}

# Sanity: the package literal by itself still stringifies correctly.
is(Foo::Bar::, 'Foo::Bar', 'package literal evaluates to class name');

