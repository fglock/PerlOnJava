#!/usr/bin/env perl

use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Storable qw(store retrieve nstore freeze thaw nfreeze dclone);

# Test plan
plan tests => 8;

subtest 'Basic scalar serialization' => sub {
    plan tests => 6;
    
    # Test undef
    my $undef_val = undef;
    my $frozen = freeze(\$undef_val);
    ok(defined $frozen, 'freeze(\\undef) returns defined value');
    my $thawed = thaw($frozen);
    ok(!defined $$thawed, 'thaw restores undef correctly');
    
    # Test string
    my $string = "Hello, World!";
    $frozen = freeze(\$string);
    $thawed = thaw($frozen);
    is($$thawed, $string, 'String round-trip works');
    
    # Test integer
    my $int = 42;
    $frozen = freeze(\$int);
    $thawed = thaw($frozen);
    is($$thawed, $int, 'Integer round-trip works');
    
    # Test float
    my $float = 3.14159;
    $frozen = freeze(\$float);
    $thawed = thaw($frozen);
    is($$thawed, $float, 'Float round-trip works');
    
    # Test boolean
    my $bool = 1;
    $frozen = freeze(\$bool);
    $thawed = thaw($frozen);
    is($$thawed, $bool, 'Boolean round-trip works');
};

subtest 'Array reference serialization' => sub {
    plan tests => 4;
    
    # Test empty array
    my $empty_array = [];
    my $frozen = freeze($empty_array);
    my $thawed = thaw($frozen);
    is_deeply($thawed, $empty_array, 'Empty array round-trip works');
    
    # Test simple array
    my $simple_array = [1, 2, 3, "hello"];
    $frozen = freeze($simple_array);
    $thawed = thaw($frozen);
    is_deeply($thawed, $simple_array, 'Simple array round-trip works');
    
    # Test nested array
    my $nested_array = [1, [2, 3], [4, [5, 6]]];
    $frozen = freeze($nested_array);
    $thawed = thaw($frozen);
    is_deeply($thawed, $nested_array, 'Nested array round-trip works');
    
    # Test mixed types
    my $mixed_array = [42, "string", 3.14, undef, [1, 2]];
    $frozen = freeze($mixed_array);
    $thawed = thaw($frozen);
    is_deeply($thawed, $mixed_array, 'Mixed type array round-trip works');
};

subtest 'Hash reference serialization' => sub {
    plan tests => 4;
    
    # Test empty hash
    my $empty_hash = {};
    my $frozen = freeze($empty_hash);
    my $thawed = thaw($frozen);
    is_deeply($thawed, $empty_hash, 'Empty hash round-trip works');
    
    # Test simple hash
    my $simple_hash = { a => 1, b => 2, c => "hello" };
    $frozen = freeze($simple_hash);
    $thawed = thaw($frozen);
    is_deeply($thawed, $simple_hash, 'Simple hash round-trip works');
    
    # Test nested hash
    my $nested_hash = {
        level1 => {
            level2 => {
                value => 42
            }
        }
    };
    $frozen = freeze($nested_hash);
    $thawed = thaw($frozen);
    is_deeply($thawed, $nested_hash, 'Nested hash round-trip works');
    
    # Test mixed structure
    my $mixed_hash = {
        string => "hello",
        number => 42,
        float => 3.14,
        array => [1, 2, 3],
        hash => { nested => "value" },
        undef => undef
    };
    $frozen = freeze($mixed_hash);
    $thawed = thaw($frozen);
    is_deeply($thawed, $mixed_hash, 'Mixed structure hash round-trip works');
};

subtest 'Blessed object serialization' => sub {
    plan tests => 4;
    
    # Create a simple blessed object
    package TestClass;
    sub new {
        my $class = shift;
        my $self = { name => shift, value => shift };
        return bless $self, $class;
    }
    
    package main;
    
    # Test simple blessed object
    my $obj = TestClass->new("test", 42);
    my $frozen = freeze($obj);
    my $thawed = thaw($frozen);
    
    isa_ok($thawed, 'TestClass', 'Object class preserved');
    is($thawed->{name}, "test", 'Object data preserved - name');
    is($thawed->{value}, 42, 'Object data preserved - value');
    
    # Test nested blessed object
    my $nested_obj = {
        obj => TestClass->new("nested", 123),
        data => "other data"
    };
    $frozen = freeze($nested_obj);
    $thawed = thaw($frozen);
    
    isa_ok($thawed->{obj}, 'TestClass', 'Nested object class preserved');
};

subtest 'Circular reference handling' => sub {
    plan tests => 3;
    
    # Test simple circular reference
    my $circular = {};
    $circular->{self} = $circular;
    
    my $frozen = freeze($circular);
    ok(defined $frozen, 'Circular reference can be frozen');
    
    my $thawed = thaw($frozen);
    ok(defined $thawed, 'Circular reference can be thawed');
    is($thawed->{self}, $thawed, 'Circular reference preserved');
};

subtest 'File operations' => sub {
    plan tests => 6;
    
    my ($fh, $filename) = tempfile();
    close $fh;
    
    # Test store/retrieve
    my $data = { test => "data", number => 42, array => [1, 2, 3] };
    
    my $result = store($data, $filename);
    ok($result, 'store() returns success');
    ok(-f $filename, 'File was created');
    
    my $retrieved = retrieve($filename);
    is_deeply($retrieved, $data, 'Retrieved data matches original');
    
    # Test nstore (should work the same)
    my $ndata = { network => "test", value => 123 };
    $result = nstore($ndata, $filename);
    ok($result, 'nstore() returns success');
    
    $retrieved = retrieve($filename);
    is_deeply($retrieved, $ndata, 'nstore/retrieve round-trip works');
    
    # Clean up
    unlink $filename;
    ok(!-f $filename, 'Test file cleaned up');
};

subtest 'Network byte order functions' => sub {
    plan tests => 2;
    
    my $data = { test => "network", numbers => [1, 2, 3, 4] };
    
    # Test nfreeze
    my $nfrozen = nfreeze($data);
    ok(defined $nfrozen, 'nfreeze() returns defined value');
    
    my $thawed = thaw($nfrozen);
    is_deeply($thawed, $data, 'nfreeze/thaw round-trip works');
};

subtest 'Deep cloning' => sub {
    plan tests => 4;
    
    # Test simple clone
    my $original = { a => 1, b => [2, 3, 4] };
    my $clone = dclone($original);
    
    is_deeply($clone, $original, 'Clone matches original');
    
    # Modify clone to ensure it's independent
    $clone->{a} = 999;
    $clone->{b}->[0] = 888;
    
    is($original->{a}, 1, 'Original unchanged after modifying clone');
    is($original->{b}->[0], 2, 'Original nested data unchanged');
    
    # Test blessed object cloning
    package CloneTest;
    sub new { bless { value => $_[1] }, $_[0] }
    
    package main;
    
    my $blessed_original = CloneTest->new(42);
    my $blessed_clone = dclone($blessed_original);
    
    isa_ok($blessed_clone, 'CloneTest', 'Cloned blessed object preserves class');
};

done_testing();
