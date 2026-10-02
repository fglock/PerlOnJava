use strict;
use warnings;
use constant MyClass => 'Foo::Bar::Biz::Baz';
{
    package Foo::Bar::Biz::Baz;
    1;
}
my $compiled = eval 'sub { my MyClass $value = shift; }';
print "1..1\n";
print((defined($compiled) && !$@ ? 'ok' : 'not ok'),
    " 1 - eval resolves a constant class name in a typed lexical\n");
