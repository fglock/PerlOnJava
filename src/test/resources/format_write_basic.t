#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

# Test Perl format write functionality in PerlOnJava
# This test suite verifies the BASIC format infrastructure that is currently working
# NOTE: Variable evaluation in format fields is NOT yet implemented
# Formats with variables ($name, @<<<, etc.) will not work as expected yet

subtest 'Basic Format Write Operations' => sub {
    plan tests => 3;
    
    # Test 1: Simple format write (literal text)
    format TEST_SIMPLE =
Hello from PerlOnJava format!
.
    
    ok(defined *TEST_SIMPLE{FORMAT}, 'Simple format is defined');
    
    # Test write operation executes without error
    my $result;
    eval {
        $result = write TEST_SIMPLE;
    };
    ok(!$@, 'Simple format write executes without error');
    ok(defined($result), 'Write operation returns success');
};

subtest 'Format Declaration and Reference' => sub {
    plan tests => 4;
    
    # Test format declaration
    format DECLARATION_TEST =
This is a test format
Multiple lines supported
.
    
    ok(defined *DECLARATION_TEST{FORMAT}, 'Format is defined after declaration');
    isa_ok(*DECLARATION_TEST{FORMAT}, 'FORMAT', 'Format reference has correct type');
    
    # Test format persistence
    my $format_ref = *DECLARATION_TEST{FORMAT};
    ok(defined $format_ref, 'Format reference can be stored');
    is(*DECLARATION_TEST{FORMAT}, $format_ref, 'Multiple accesses return same reference');
};

subtest 'Format Typeglob Operations' => sub {
    plan tests => 3;
    
    # Create original format
    format ORIGINAL_BASIC =
Original format for copying
.
    
    ok(defined *ORIGINAL_BASIC{FORMAT}, 'Original format is defined');
    
    # Copy format via typeglob assignment
    *COPIED_BASIC = *ORIGINAL_BASIC;
    ok(defined *COPIED_BASIC{FORMAT}, 'Format copied via typeglob assignment');
    
    # Test that they reference the same format object
    is(*COPIED_BASIC{FORMAT}, *ORIGINAL_BASIC{FORMAT}, 'Copied format references same object');
};

subtest 'Format Namespace Operations' => sub {
    plan tests => 4;
    
    # Test format in main namespace
    format MAIN_FORMAT_BASIC =
Main namespace format
.
    
    ok(defined *MAIN_FORMAT_BASIC{FORMAT}, 'Format defined in main namespace');
    ok(defined *main::MAIN_FORMAT_BASIC{FORMAT}, 'Format accessible with main:: prefix');
    
    # Test format in custom package
    {
        package TestFormatPackage;
        
        format PACKAGE_FORMAT_BASIC =
Package format content
.
        
        main::ok(defined *PACKAGE_FORMAT_BASIC{FORMAT}, 'Format defined in custom package');
        main::ok(defined *TestFormatPackage::PACKAGE_FORMAT_BASIC{FORMAT}, 'Format accessible with package prefix');
    }
};

subtest 'Format Write Execution' => sub {
    plan tests => 2;
    
    # Test that write operations execute successfully
    format EXECUTION_TEST =
Testing format execution
.
    
    my $write_result;
    eval {
        $write_result = write EXECUTION_TEST;
    };
    
    ok(!$@, 'Format write executes without throwing exception');
    ok(defined $write_result, 'Write operation returns a defined result');
};

subtest 'Format Error Conditions' => sub {
    plan tests => 2;
    
    # Test writing to nonexistent format
    eval {
        write NONEXISTENT_FORMAT_TEST;
    };
    ok($@, 'Writing to nonexistent format produces error');
    
    # Test format persistence after write
    format PERSISTENT_TEST =
This format should persist after write
.
    
    eval { write PERSISTENT_TEST; };
    ok(defined *PERSISTENT_TEST{FORMAT}, 'Format remains defined after write operation');
};

# Run all tests
done_testing();
