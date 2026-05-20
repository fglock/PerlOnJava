use strict;
use warnings;
use Test::More tests => 4;
use NEXT;

{
    package NextPrereq::Foo;
    use mro 'c3';
    sub foo { 'Foo::foo' }

    package NextPrereq::Fuz;
    use mro 'c3';
    use base 'NextPrereq::Foo';
    sub foo { 'Fuz::foo => ' . (shift)->next::method }

    package NextPrereq::Bar;
    use mro 'c3';
    use base 'NextPrereq::Foo';
    sub foo { 'Bar::foo => ' . (shift)->next::method }

    package NextPrereq::Baz;
    use base 'NextPrereq::Bar', 'NextPrereq::Fuz';
    sub foo { 'Baz::foo => ' . (shift)->NEXT::foo }
}

is(NextPrereq::Foo->foo, 'Foo::foo', 'NEXT.pm is loadable');
is(NextPrereq::Fuz->foo, 'Fuz::foo => Foo::foo', 'next::method still works');
is(NextPrereq::Bar->foo, 'Bar::foo => Foo::foo', 'next::method works in sibling class');
is(NextPrereq::Baz->foo, 'Baz::foo => Bar::foo => Fuz::foo => Foo::foo',
    'NEXT redispatch works with c3 next::method');
