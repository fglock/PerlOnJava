#!/usr/bin/env perl

use strict;
use warnings;
use Test::More tests => 10;

{
    package CodeRefDefinedExists::Named;
    sub real { 42 }
    sub stub;
}

my $real = \&CodeRefDefinedExists::Named::real;
ok(defined &$real, 'defined &$coderef is true for a named sub');
ok(exists &$real, 'exists &$coderef is true for a named sub');

my $stub = \&CodeRefDefinedExists::Named::stub;
ok(defined $stub, 'forward-declared CODE reference is a defined scalar');
ok(!defined &$stub, 'defined &$coderef is false for a forward declaration');
ok(exists &$stub, 'exists &$coderef is true for a forward declaration');

my $anon = sub { 42 };
ok(defined &$anon, 'defined &$coderef is true for an anonymous sub');
ok(exists &$anon, 'exists &$coderef is true for an anonymous sub');

{
    no strict 'refs';

    my $symbolic = \&{'CodeRefDefinedExists::Named::real'};
    ok(defined &$symbolic, 'defined &$coderef is true for a symbolic named sub');

    my $missing = \&{'CodeRefDefinedExists::Named::missing'};
    ok(!defined &$missing, 'defined &$coderef is false for a symbolic missing sub');
    ok(exists &$missing, 'exists &$coderef is true after symbolic CODE ref creation');
}
