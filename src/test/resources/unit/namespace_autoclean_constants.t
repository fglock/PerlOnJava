#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

BEGIN {
    eval { require Moose; require namespace::autoclean; 1 }
        or plan skip_all => 'Moose and namespace::autoclean required';
}

plan tests => 4;

{
    package NamespaceAutocleanConstantTest;
    use Moose;
    use namespace::autoclean;
    use constant FOO_BAR => 7;
    use constant lower_const => 'value';

    no Moose;
    __PACKAGE__->meta->make_immutable;
}

ok(defined &NamespaceAutocleanConstantTest::FOO_BAR, 'uppercase constant survives namespace::autoclean');
is(NamespaceAutocleanConstantTest->FOO_BAR, 7, 'uppercase constant is callable as a class method');
ok(defined &NamespaceAutocleanConstantTest::lower_const, 'lowercase constant survives namespace::autoclean');
is(NamespaceAutocleanConstantTest->lower_const, 'value', 'lowercase constant is callable as a class method');
