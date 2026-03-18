#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test: local on package variables with 'our' aliases
# This tests that 'local $Foo::X' affects '$X' accessed via 'our' inside subroutines

plan tests => 12;

#
# Test 1-3: Basic 'our' variable access across package changes
#
{
    package TestPkg1;
    our $x = 10;
    
    package main;
    is($TestPkg1::x, 10, 'fully qualified access works');
}

{
    our $x = 20;
    package OtherPkg;
    Test::More::is($x, 20, 'our alias persists after package change');
}

{
    package TestPkg2;
    our $y = 30;
    sub get_y { return $y; }
    
    package main;
    is(TestPkg2::get_y(), 30, 'our variable accessible in subroutine');
}

#
# Test 4-6: local on package variable - subroutine should see localized value
#
{
    package Foo;
    our $X = 0;
    sub check { return $X; }
    
    package main;
    is(Foo::check(), 0, 'before local: subroutine sees original value');
    {
        local $Foo::X = 1;
        is(Foo::check(), 1, 'inside local: subroutine sees localized value');  # FAILS in jperl
    }
    is(Foo::check(), 0, 'after local: subroutine sees restored value');
}

#
# Test 7-9: local from different package
#
{
    package Bar;
    our $VAR = 100;
    sub get_var { return $VAR; }
    
    package main;
    is(Bar::get_var(), 100, 'cross-package: before local');
    {
        local $Bar::VAR = 200;
        is(Bar::get_var(), 200, 'cross-package: inside local');  # FAILS in jperl
    }
    is(Bar::get_var(), 100, 'cross-package: after local');
}

#
# Test 10-12: our alias with local from yet another package
#
{
    our $main_var = 'original';
    sub check_main_var { return $main_var; }
    
    is(check_main_var(), 'original', 'main our: before local');
    
    {
        package AnotherPkg;
        local $main::main_var = 'localized';
        Test::More::is(main::check_main_var(), 'localized', 'main our: inside local from other pkg');  # FAILS in jperl
    }
    
    is(check_main_var(), 'original', 'main our: after local');
}

done_testing();
