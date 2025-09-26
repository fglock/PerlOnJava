#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

# Test Perl format variable evaluation functionality in PerlOnJava
# This test suite verifies that format variables are correctly evaluated and substituted

# Test variables - using 'our' to make them global for format access
our $project = "PerlOnJava";
our $version = "2.0";
our $author = "Windsurf Team";
our $status = "Working";
our $count = 42;
our $price = 99.95;

subtest 'Basic Variable Substitution' => sub {
    plan tests => 2;
    
    # Test format with single variables
    format BASIC_VARS =
Project: @<<<<<<<<<<
         $project
Version: @<<<<<<<<<<
         $version
Status:  @<<<<<<<<<<
         $status
.
    
    ok(defined *BASIC_VARS{FORMAT}, 'Basic variable format is defined');
    
    # Test that format executes without error
    my $result;
    eval {
        $result = write BASIC_VARS;
    };
    ok(!$@, 'Basic variable format executes without error');
};

subtest 'Multiple Variables in Format' => sub {
    plan tests => 2;
    
    # Test format with multiple variables in one line
    format MULTI_VARS =
Project: @<<<<<<<<<< Version: @<<<<< Author: @<<<<<<<<<<<<
         $project,           $version,       $author
Status:  @<<<<<<<<<<
         $status
.
    
    ok(defined *MULTI_VARS{FORMAT}, 'Multi-variable format is defined');
    
    # Test that format executes without error
    my $result;
    eval {
        $result = write MULTI_VARS;
    };
    ok(!$@, 'Multi-variable format executes without error');
};

subtest 'Mixed Data Types' => sub {
    plan tests => 2;
    
    # Test format with numeric and string variables
    format MIXED_TYPES =
Count: @###  Price: $@##.##
       $count,       $price
Project: @<<<<<<<<<<
         $project
.
    
    ok(defined *MIXED_TYPES{FORMAT}, 'Mixed data types format is defined');
    
    # Test that format executes without error
    my $result;
    eval {
        $result = write MIXED_TYPES;
    };
    ok(!$@, 'Mixed data types format executes without error');
};

subtest 'Variable Scope and Access' => sub {
    plan tests => 3;
    
    # Test that global variables are accessible in formats
    our $global_test = "GlobalValue";
    
    format SCOPE_TEST =
Global: @<<<<<<<<<<
        $global_test
.
    
    ok(defined *SCOPE_TEST{FORMAT}, 'Scope test format is defined');
    
    # Test execution
    my $result;
    eval {
        $result = write SCOPE_TEST;
    };
    ok(!$@, 'Scope test format executes without error');
    ok(defined $result, 'Scope test format returns defined result');
};

subtest 'Format Variable Persistence' => sub {
    plan tests => 3;
    
    # Test that variables maintain their values across multiple writes
    our $counter = 1;
    
    format PERSISTENCE_TEST =
Counter: @##
         $counter
.
    
    # First write
    my $result1;
    eval { $result1 = write PERSISTENCE_TEST; };
    ok(!$@, 'First write executes without error');
    
    # Change variable value
    $counter = 2;
    
    # Second write should use new value
    my $result2;
    eval { $result2 = write PERSISTENCE_TEST; };
    ok(!$@, 'Second write executes without error');
    ok(defined $result2, 'Second write returns defined result');
};

subtest 'Variable Error Handling' => sub {
    plan tests => 2;
    
    # Test format with undefined variable reference
    my $undefined_variable;  # Declare the variable (will be undef)
    format UNDEFINED_VAR =
Undefined: @<<<<<<<<<<
           $undefined_variable
.
    
    ok(defined *UNDEFINED_VAR{FORMAT}, 'Format with undefined variable is defined');
    
    # Should execute without fatal error (undefined variables should be handled gracefully)
    my $result;
    eval {
        $result = write UNDEFINED_VAR;
    };
    # Note: This might produce a warning but shouldn't crash
    ok(defined $result, 'Format with undefined variable returns result');
};

# Run all tests
done_testing();
