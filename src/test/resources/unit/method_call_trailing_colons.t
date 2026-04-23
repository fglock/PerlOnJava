use strict;
use warnings;
use Test::More tests => 5;

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

