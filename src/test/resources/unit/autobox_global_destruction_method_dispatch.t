#!/usr/bin/env perl
use strict;
use warnings;

print '1..1', $/;

{
    package Foo;
    use autobox;

    sub DESTROY {
        eval { undef->bar };
        if ($@ =~ /^Can't call method "bar" on an undefined value/) {
            print 'ok 1', $/;
        } else {
            print 'not ok 1', $/;
        }
    }
}

{
    package Bar;
    sub unused { }
}

my $foo = bless {}, 'Foo';
my $bar = bless {}, 'Bar';
$foo->{bar} = $bar;
$bar->{foo} = $foo;
