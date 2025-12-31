#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test 1: Basic &$var() call with no strict refs
{
    no strict 'refs';
    my $subname = "main::test_sub1";
    *$subname = sub { return "test1_result" };
    my $result = &$subname();
    is($result, "test1_result", "&\$var() works with string containing glob name");
}

# Test 2: &$var() with arguments
{
    no strict 'refs';
    my $subname = "main::test_sub2";
    *$subname = sub { return join(",", @_) };
    my $result = &$subname("a", "b", "c");
    is($result, "a,b,c", "&\$var() passes arguments correctly");
}

# Test 3: goto &$var with AUTOLOAD pattern
{
    package TestAutoload;
    use vars qw($AUTOLOAD);
    
    # No forward declaration - let AUTOLOAD handle it
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        my $name = $AUTOLOAD;  # Save the name before goto
        no strict 'refs';
        *$AUTOLOAD = sub { return "autoloaded:$name" };
        goto &$AUTOLOAD;
    }
    
    package main;
    my $result = TestAutoload::test_method();
    is($result, "autoloaded:TestAutoload::test_method", "goto &\$AUTOLOAD works in AUTOLOAD");
}

# Test 4: &$var() where $var is already a CODE reference
{
    my $coderef = sub { return "direct_coderef" };
    no strict 'refs';
    my $result = &$coderef();
    is($result, "direct_coderef", "&\$var() works when var is already a CODE reference");
}

# Test 5: $var->() syntax with no strict refs
{
    no strict 'refs';
    my $subname = "main::test_sub5";
    *$subname = sub { return "arrow_syntax" };
    my $result = $subname->();
    is($result, "arrow_syntax", "\$var->() works with string containing glob name");
}

# Test 6: goto &$var preserves @_
{
    package TestGoto;
    use vars qw($AUTOLOAD);
    
    sub wrapper {
        my @original_args = @_;
        no strict 'refs';
        my $target = "TestGoto::target";
        *$target = sub { return "args:" . join(",", @_) };
        goto &$target;
    }
    
    sub target { }  # This will be replaced by goto
    
    package main;
    my $result = TestGoto::wrapper("x", "y", "z");
    is($result, "args:x,y,z", "goto &\$var preserves \@_ correctly");
}

# Test 7: Multiple calls to same dynamically created sub
{
    no strict 'refs';
    my $subname = "main::test_sub7";
    *$subname = sub { return "persistent" };
    my $result1 = &$subname();
    my $result2 = &$subname();
    is($result1, "persistent", "First call to dynamic sub works");
    is($result2, "persistent", "Second call to same dynamic sub works");
}

# Test 8: Forward-declared but undefined sub should trigger AUTOLOAD
{
    package TestForwardDecl;
    use vars qw($AUTOLOAD);
    
    sub declared_but_undefined;  # Forward declaration
    
    sub AUTOLOAD {
        our $AUTOLOAD;
        return "autoloaded:$AUTOLOAD";
    }
    
    package main;
    no strict 'refs';
    my $name = "TestForwardDecl::declared_but_undefined";
    my $result = &$name();
    is($result, "autoloaded:TestForwardDecl::declared_but_undefined", 
       "Forward-declared undefined sub triggers AUTOLOAD when called via &\$name");
}

# Test 9: Forward-declared undefined sub without AUTOLOAD should die
{
    package TestNoDeclNoAutoload;
    
    sub another_undefined;  # Forward declaration, no AUTOLOAD
    
    package main;
    no strict 'refs';
    my $name = "TestNoDeclNoAutoload::another_undefined";
    eval { &$name() };
    like($@, qr/Undefined subroutine/, 
         "Forward-declared undefined sub without AUTOLOAD throws error");
}

done_testing();
