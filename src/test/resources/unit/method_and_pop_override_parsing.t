use strict;
use warnings;
use Test::More;

{
    package LegacyMethod;
    sub method { shift; join ',', 'method', @_ }

    package main;
    is(method LegacyMethod ('a', 'b'), 'method,a,b',
       'method Package (LIST) remains an indirect method call');
    is((method LegacyMethod 'c', 'd'), 'method,c,d',
       'parenthesis-free indirect method call remains supported');
}

{
    package ForwardStubParent;
    sub AUTOLOAD {
        our $AUTOLOAD;
        return "autoloaded $AUTOLOAD";
    }

    package ForwardStubChild;
    our @ISA = ('ForwardStubParent');
    sub missing;

    package main;
    is(ForwardStubChild->missing, 'autoloaded ForwardStubChild::missing',
       'a declared method stub uses an inherited AUTOLOAD');
}

BEGIN {
    *LocalPopOverride::pop = sub { $main::pop_override_result = $_[0][0] };
}
{
    package LocalPopOverride;
    sub values { ['overridden'] }

    ::is(pop(LocalPopOverride->values()), 'overridden',
         'parenthesized package-local pop override is called');
    pop LocalPopOverride->values();
    ::is($main::pop_override_result, 'overridden',
         'parenthesis-free package-local pop override is called');
}

done_testing;
