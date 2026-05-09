use strict;
use warnings;

package X;

use Test::More tests => 6;

sub x {
    callme(@_);
}

sub a {
    another_missing_sub(@_);
}

sub b {
    yet_another_missing_sub(@_);
}

sub AUTOLOAD {
    our $AUTOLOAD;
    if ($AUTOLOAD eq 'X::callme' && @_ == 1 && $_[0] == 123) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } elsif ($AUTOLOAD eq 'X::another_missing_sub' && @_ == 1 && $_[0] == 456) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } elsif ($AUTOLOAD eq 'X::yet_another_missing_sub' && @_ == 1 && $_[0] == 789) {
        pass("autoloading: $AUTOLOAD called with correct args <@_>");
    } else {
        fail("unexpected AUTOLOAD call: $AUTOLOAD <@_>");
    }
}

x(123);
a(456);
b(789);

# Test that inherited AUTOLOAD sets $AUTOLOAD with the child class name
{
    package BaseWithAutoload;
    sub AUTOLOAD {
        our $AUTOLOAD;
        return $AUTOLOAD;
    }

    package ChildClass;
    our @ISA = ("BaseWithAutoload");

    package main;
    my $obj = bless {}, "ChildClass";
    X::is($obj->somefunc(), "ChildClass::somefunc",
        "inherited AUTOLOAD sets \$AUTOLOAD with child class name");
    X::is($obj->anotherfunc(), "ChildClass::anotherfunc",
        "inherited AUTOLOAD works for multiple method calls");
}

# Regression: cached AUTOLOAD method calls must keep method-chain
# temporaries alive until the AUTOLOAD body has read $AUTOLOAD. Without
# the cached-call mortal boundary, the temporary invocant's DESTROY
# AUTOLOAD overwrote $AUTOLOAD between dispatch and entry.
{
    package ChainAutoloadBase;
    sub missing;
    sub AUTOLOAD {
        our $AUTOLOAD;
        my $method = $AUTOLOAD;
        $method =~ s/.*:://;
        return if $method eq 'DESTROY';
        return "$method:" . ref($_[0]);
    }

    package ChainAutoloadChild;
    our @ISA = ("ChainAutoloadBase");
    sub new { bless {}, shift }

    package main;
    my $first = ChainAutoloadChild->new->missing;
    my $second = ChainAutoloadChild->new->missing;
    X::is($second, "missing:ChainAutoloadChild",
        "cached inherited AUTOLOAD resets \$AUTOLOAD before chained call body");
}

1;
