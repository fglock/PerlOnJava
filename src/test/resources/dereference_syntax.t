#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test double dereference syntax: $$arrayref[index] and $$hashref{key}
# These should be equivalent to $arrayref->[index] and $hashref->{key}

subtest 'Basic array double dereference' => sub {
    plan tests => 6;
    
    my @array = ('first', 'second', 'third');
    my $ref = \@array;
    
    # Test $$ref[index] syntax
    is($$ref[0], 'first', '$$ref[0] returns first element');
    is($$ref[1], 'second', '$$ref[1] returns second element');
    is($$ref[2], 'third', '$$ref[2] returns third element');
    
    # Verify equivalence with arrow syntax
    is($$ref[0], $ref->[0], '$$ref[0] equals $ref->[0]');
    is($$ref[1], $ref->[1], '$$ref[1] equals $ref->[1]');
    is($$ref[2], $ref->[2], '$$ref[2] equals $ref->[2]');
};

subtest 'Basic hash double dereference' => sub {
    plan tests => 6;
    
    my %hash = (
        key1 => 'value1',
        key2 => 'value2',
        key3 => 'value3',
    );
    my $ref = \%hash;
    
    # Test $$ref{key} syntax
    is($$ref{key1}, 'value1', '$$ref{key1} returns value1');
    is($$ref{key2}, 'value2', '$$ref{key2} returns value2');
    is($$ref{key3}, 'value3', '$$ref{key3} returns value3');
    
    # Verify equivalence with arrow syntax
    is($$ref{key1}, $ref->{key1}, '$$ref{key1} equals $ref->{key1}');
    is($$ref{key2}, $ref->{key2}, '$$ref{key2} equals $ref->{key2}');
    is($$ref{key3}, $ref->{key3}, '$$ref{key3} equals $ref->{key3}');
};

subtest 'Array of arrays (AoA) double dereference' => sub {
    plan tests => 4;
    
    my @tests = (
        ["foo", "bar", "baz"],
        ["one", "two", "three"],
        ["alpha", "beta", "gamma"],
    );
    
    foreach my $test (@tests) {
        # This is the pattern used in t/op/index.t that was failing
        my $first = $$test[0];
        ok(defined $first, "$$test[0] is defined");
    }
    
    # Specific test case from index.t
    my $test = ["foo", 1, 2];
    is($$test[0], 'foo', '$$test[0] from array ref returns correct value');
};

subtest 'Hash of hashes (HoH) double dereference' => sub {
    plan tests => 3;
    
    my %data = (
        user1 => { name => 'Alice', age => 30 },
        user2 => { name => 'Bob', age => 25 },
    );
    
    my $user1_ref = $data{user1};
    is($$user1_ref{name}, 'Alice', '$$hashref{key} for nested hash');
    is($$user1_ref{age}, 30, '$$hashref{key} for numeric value');
    
    my $user2_ref = $data{user2};
    is($$user2_ref{name}, 'Bob', '$$hashref{key} for different hash');
};

subtest 'Assignment through double dereference' => sub {
    plan tests => 4;
    
    # Array assignment
    my @array = (1, 2, 3);
    my $aref = \@array;
    $$aref[1] = 42;
    is($array[1], 42, 'Assignment through $$ref[index] modifies array');
    is($$aref[1], 42, 'Reading back through $$ref[index] works');
    
    # Hash assignment
    my %hash = (key => 'old');
    my $href = \%hash;
    $$href{key} = 'new';
    is($hash{key}, 'new', 'Assignment through $$ref{key} modifies hash');
    is($$href{key}, 'new', 'Reading back through $$ref{key} works');
};

subtest 'Edge cases and special scenarios' => sub {
    plan tests => 5;
    
    # Undefined index
    my @array = (1, 2, 3);
    my $aref = \@array;
    is($$aref[10], undef, '$$ref[out_of_bounds] returns undef');
    
    # Non-existent hash key
    my %hash = (key => 'value');
    my $href = \%hash;
    is($$href{nonexistent}, undef, '$$ref{nonexistent_key} returns undef');
    
    # Empty array
    my @empty = ();
    my $empty_ref = \@empty;
    is($$empty_ref[0], undef, '$$ref[0] on empty array returns undef');
    
    # Empty hash
    my %empty_hash = ();
    my $empty_href = \%empty_hash;
    is($$empty_href{key}, undef, '$$ref{key} on empty hash returns undef');
    
    # Numeric hash key
    my %num_hash = (42 => 'answer');
    my $num_href = \%num_hash;
    is($$num_href{42}, 'answer', '$$ref{numeric_key} works');
};

subtest 'Mixed references in loops' => sub {
    plan tests => 6;
    
    # Pattern similar to op/index.t
    my @test_data = (
        ["", -1, -1, -1],
        ["foo", -1, -1, -1],
        ["x", 0, -1, -1],
    );
    
    my $i = 0;
    foreach my $test (@test_data) {
        my $str = $$test[0];
        ok(defined $str || $str eq '', 'String extracted from $$test[0]');
        my $expected = ($i == 2) ? 0 : -1;  # Third test has 0, others have -1
        my $val = $$test[1];
        is($val, $expected, "Numeric value extracted from $$test[1] is $expected");
        $i++;
    }
};

done_testing();
