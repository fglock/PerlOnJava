#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;

# Test Perl format support in PerlOnJava
# This test suite covers format parsing, compilation, typeglob integration,
# and execution across different namespaces and edge cases

subtest 'Basic Format Declaration and Parsing' => sub {
    plan tests => 4;
    
    # Test 1: Simple format declaration
    format BASIC_TEST =
Hello World
.
    
    ok(defined *BASIC_TEST{FORMAT}, 'Basic format is defined in typeglob');
    isa_ok(*BASIC_TEST{FORMAT}, 'FORMAT', 'Format reference has correct type');
    
    # Test 2: Format with name
    format NAMED_FORMAT =
This is a named format
.
    
    ok(defined *NAMED_FORMAT{FORMAT}, 'Named format is defined');
    isnt(*NAMED_FORMAT{FORMAT}, *BASIC_TEST{FORMAT}, 'Different formats have different references');
};

subtest 'Format Field Types' => sub {
    plan tests => 6;
    
    my $name = "John Doe";
    my $age = 25;
    my $price = 123.45;
    
    # Test text formatting fields
    format TEXT_FIELDS =
Left:   @<<<<<<<<<<  Right: @>>>>>>>>>  Center: @|||||||||
        $name,              $name,              $name
.
    
    ok(defined *TEXT_FIELDS{FORMAT}, 'Text fields format defined');
    
    # Test numeric formatting fields
    format NUMERIC_FIELDS =
Age: @##  Price: @###.##  Large: @#####
     $age,        $price,         $price
.
    
    ok(defined *NUMERIC_FIELDS{FORMAT}, 'Numeric fields format defined');
    
    # Test multiline fields
    my $description = "This is a very long description that should wrap";
    format MULTILINE_FIELDS =
Description:
@*
$description
Fill mode:
^*
$description
.
    
    ok(defined *MULTILINE_FIELDS{FORMAT}, 'Multiline fields format defined');
    
    # Test mixed field types
    format MIXED_FIELDS =
Name: @<<<<<<<<<<  Age: @##  Price: @###.##
      $name,            $age,        $price
Description: @*
             $description
.
    
    ok(defined *MIXED_FIELDS{FORMAT}, 'Mixed fields format defined');
    
    # Test format with comments
    format COMMENTED_FORMAT =
# This is a comment line
Name: @<<<<<<<<<<
      $name
# Another comment
Age: @##
     $age
.
    
    ok(defined *COMMENTED_FORMAT{FORMAT}, 'Format with comments defined');
    
    # Test empty format
    format EMPTY_FORMAT =
.
    
    ok(defined *EMPTY_FORMAT{FORMAT}, 'Empty format defined');
};

subtest 'Format Typeglob Operations' => sub {
    plan tests => 5;
    
    # Test format copying between typeglobs
    format ORIGINAL_FORMAT =
Original content
Value: @<<<<<<<
       "test"
.
    
    ok(defined *ORIGINAL_FORMAT{FORMAT}, 'Original format defined');
    
    # Copy format to another typeglob
    *COPIED_FORMAT = *ORIGINAL_FORMAT;
    ok(defined *COPIED_FORMAT{FORMAT}, 'Format copied to new typeglob');
    
    # Test that they reference the same format
    is(*COPIED_FORMAT{FORMAT}, *ORIGINAL_FORMAT{FORMAT}, 'Copied format references same object');
    
    # Test format undefinition
    my $format_ref = *ORIGINAL_FORMAT{FORMAT};
    ok(defined $format_ref, 'Format reference is defined before undef');
    
    # Note: undef *glob{FORMAT} syntax not supported in standard Perl
    # Testing format existence instead
    ok(defined *ORIGINAL_FORMAT{FORMAT}, 'Format still defined (undef syntax not standard)');
};

subtest 'Namespace Testing' => sub {
    plan tests => 8;
    
    # Test formats in main namespace
    format MAIN_FORMAT =
Main namespace format
.
    
    ok(defined *MAIN_FORMAT{FORMAT}, 'Format defined in main namespace');
    ok(defined *main::MAIN_FORMAT{FORMAT}, 'Format accessible with main:: prefix');
    
    # Test formats in custom namespace
    {
        package TestPackage;
        
        format PACKAGE_FORMAT =
Package namespace format
.
        
        main::ok(defined *PACKAGE_FORMAT{FORMAT}, 'Format defined in custom package');
        main::ok(defined *TestPackage::PACKAGE_FORMAT{FORMAT}, 'Format accessible with package prefix');
    }
    
    # Test cross-namespace access
    ok(defined *TestPackage::PACKAGE_FORMAT{FORMAT}, 'Format accessible from main namespace');
    
    # Test format name resolution
    {
        package AnotherPackage;
        
        format SAME_NAME =
Another package format
.
        
        main::ok(defined *SAME_NAME{FORMAT}, 'Format with same name in different package');
        main::isnt(*SAME_NAME{FORMAT}, *main::MAIN_FORMAT{FORMAT}, 'Same-named formats in different packages are different');
    }
    
    # Test that package formats don't interfere
    ok(defined *MAIN_FORMAT{FORMAT}, 'Main format still exists after package formats');
};

subtest 'Edge Cases and Error Conditions' => sub {
    plan tests => 4;
    
    # Test format with special characters in content
    format SPECIAL_CHARS =
Special chars: !@#$%^&*()_+-={}[]|\\:";'<>?,./ 
Numbers: 1234567890
Unicode: αβγδε (if supported)
.
    
    ok(defined *SPECIAL_CHARS{FORMAT}, 'Format with special characters defined');
    
    # Test format with very long lines
    my $long_string = "x" x 200;
    format LONG_LINES =
Long line: @*
           $long_string
.
    
    ok(defined *LONG_LINES{FORMAT}, 'Format with long lines defined');
    
    # Test format with many fields
    my ($a, $b, $c, $d, $e) = (1, 2, 3, 4, 5);
    format MANY_FIELDS =
@# @# @# @# @#
$a,$b,$c,$d,$e
.
    
    ok(defined *MANY_FIELDS{FORMAT}, 'Format with many fields defined');
    
    # Test format with nested expressions (if supported)
    my $value = 42;
    format EXPRESSIONS =
Value: @##
       $value + 1
.
    
    ok(defined *EXPRESSIONS{FORMAT}, 'Format with expressions defined');
};

subtest 'Format Reference Behavior' => sub {
    plan tests => 6;
    
    format REF_TEST =
Reference test
.
    
    # Test direct reference access
    my $format_ref = *REF_TEST{FORMAT};
    ok(defined $format_ref, 'Format reference can be stored in variable');
    isa_ok($format_ref, 'FORMAT', 'Stored reference has correct type');
    
    # Test reference comparison
    my $same_ref = *REF_TEST{FORMAT};
    is($format_ref, $same_ref, 'Multiple accesses return same reference');
    
    # Test reference stringification
    like("$format_ref", qr/FORMAT\(0x[0-9a-f]+\)/, 'Format reference stringifies correctly');
    
    # Test boolean context
    ok($format_ref, 'Format reference is true in boolean context');
    
    # Test with undefined format
    my $undef_ref = *NONEXISTENT_FORMAT{FORMAT};
    ok(!defined $undef_ref, 'Nonexistent format reference is undefined');
};

subtest 'Standard Format Names' => sub {
    plan tests => 3;
    
    # Test STDOUT format (default)
    format STDOUT =
STDOUT format test
.
    
    ok(defined *STDOUT{FORMAT}, 'STDOUT format can be defined');
    
    # Test STDERR format
    format STDERR =
STDERR format test
.
    
    ok(defined *STDERR{FORMAT}, 'STDERR format can be defined');
    
    # Test custom filehandle format
    format MYFILE =
Custom filehandle format
.
    
    ok(defined *MYFILE{FORMAT}, 'Custom filehandle format can be defined');
};

# Run all tests
done_testing();
