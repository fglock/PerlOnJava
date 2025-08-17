#!/usr/bin/perl
use strict;
use warnings;
use Test::More;

# Test suite to demonstrate PerlOnJava hex literal parsing issues
# This test will PASS on standard Perl but FAIL on PerlOnJava

plan tests => 6;

subtest 'Basic hex literal values' => sub {
    plan tests => 6;
    
    # Test basic hex literal interpretation
    my $val_80 = 0x80000000;
    my $val_FF = 0xFFFFFFFF;
    my $val_AA = 0xAAAAAAAA;
    
    is($val_80, 2147483648, '0x80000000 should equal 2147483648');
    is($val_FF, 4294967295, '0xFFFFFFFF should equal 4294967295');
    is($val_AA, 2863311530, '0xAAAAAAAA should equal 2863311530');
    
    # Test hex formatting
    is(sprintf("0x%08X", $val_80), "0x80000000", '0x80000000 should format correctly');
    is(sprintf("0x%08X", $val_FF), "0xFFFFFFFF", '0xFFFFFFFF should format correctly');
    is(sprintf("0x%08X", $val_AA), "0xAAAAAAAA", '0xAAAAAAAA should format correctly');
};

subtest 'Hex literal arithmetic operations' => sub {
    plan tests => 8;
    
    my $val_80 = 0x80000000;
    my $val_FF = 0xFFFFFFFF;
    
    # Test bit operations
    is($val_80 >> 31, 1, '0x80000000 >> 31 should be 1 (sign bit test)');
    is($val_FF & 0x1, 1, '0xFFFFFFFF & 0x1 should be 1 (low bit test)');
    is($val_FF & 0x80000000, 0x80000000, '0xFFFFFFFF & 0x80000000 should be 0x80000000');
    
    # Test OR operations
    is(0 | 0x80000000, 0x80000000, '0 | 0x80000000 should be 0x80000000');
    is(0 | 0xFFFFFFFF, 0xFFFFFFFF, '0 | 0xFFFFFFFF should be 0xFFFFFFFF');
    
    # Test XOR operations
    is(0xFFFFFFFF ^ 0x55555555, 0xAAAAAAAA, '0xFFFFFFFF ^ 0x55555555 should be 0xAAAAAAAA');
    
    # Test AND operations
    is(0xFFFFFFFF & 0x80000000, 0x80000000, '0xFFFFFFFF & 0x80000000 should be 0x80000000');
    is(0xAAAAAAAA & 0x80000000, 0x80000000, '0xAAAAAAAA & 0x80000000 should be 0x80000000');
};

subtest 'Array storage and retrieval' => sub {
    plan tests => 6;
    
    my @test_array = ();
    
    # Initialize 2D array
    for my $i (0 .. 9) {
        push @test_array, [0, 0];
    }
    
    # Store hex values in array
    $test_array[0][0] = 0x80000000;
    $test_array[0][1] = 0xFFFFFFFF;
    $test_array[1][0] = 0xAAAAAAAA;
    
    # Retrieve and test
    is($test_array[0][0], 0x80000000, 'Array storage: 0x80000000 should be preserved');
    is($test_array[0][1], 0xFFFFFFFF, 'Array storage: 0xFFFFFFFF should be preserved');
    is($test_array[1][0], 0xAAAAAAAA, 'Array storage: 0xAAAAAAAA should be preserved');
    
    # Test formatted output
    is(sprintf("0x%08X", $test_array[0][0]), "0x80000000", 'Array value should format as 0x80000000');
    is(sprintf("0x%08X", $test_array[0][1]), "0xFFFFFFFF", 'Array value should format as 0xFFFFFFFF');
    is(sprintf("0x%08X", $test_array[1][0]), "0xAAAAAAAA", 'Array value should format as 0xAAAAAAAA');
};

subtest 'Variable assignment vs direct literals' => sub {
    plan tests => 6;
    
    # Test variable assignment
    my $var_80 = 0x80000000;
    my $var_FF = 0xFFFFFFFF;
    my $var_AA = 0xAAAAAAAA;
    
    my $direct_80 = 0x80000000;
    my $direct_FF = 0xFFFFFFFF;
    my $direct_AA = 0xAAAAAAAA;
    
    is($var_80, $direct_80, 'Variable vs direct: 0x80000000 should be equal');
    is($var_FF, $direct_FF, 'Variable vs direct: 0xFFFFFFFF should be equal');
    is($var_AA, $direct_AA, 'Variable vs direct: 0xAAAAAAAA should be equal');
    
    # Test that both are correct values, not clamped
    is($var_80, 2147483648, 'Variable assignment: 0x80000000 should be 2147483648');
    is($var_FF, 4294967295, 'Variable assignment: 0xFFFFFFFF should be 4294967295');
    is($var_AA, 2863311530, 'Variable assignment: 0xAAAAAAAA should be 2863311530');
};

subtest 'String conversion and back' => sub {
    plan tests => 6;
    
    my $val_80 = 0x80000000;
    my $val_FF = 0xFFFFFFFF;
    my $val_AA = 0xAAAAAAAA;
    
    # Convert to string and back
    my $str_80 = sprintf("%d", $val_80);
    my $str_FF = sprintf("%d", $val_FF);
    my $str_AA = sprintf("%d", $val_AA);
    
    my $back_80 = $str_80 + 0;
    my $back_FF = $str_FF + 0;
    my $back_AA = $str_AA + 0;
    
    is($str_80, "2147483648", '0x80000000 should stringify to "2147483648"');
    is($str_FF, "4294967295", '0xFFFFFFFF should stringify to "4294967295"');
    is($str_AA, "2863311530", '0xAAAAAAAA should stringify to "2863311530"');
    
    is($back_80, $val_80, 'String->number conversion should preserve 0x80000000');
    is($back_FF, $val_FF, 'String->number conversion should preserve 0xFFFFFFFF');
    is($back_AA, $val_AA, 'String->number conversion should preserve 0xAAAAAAAA');
};

subtest 'Game of Life bit manipulation simulation' => sub {
    plan tests => 10;
    
    # Simulate the Game of Life bit operations that are failing
    my @grid = ([0, 0], [0, 0]);
    
    # Set specific bit patterns
    $grid[0][0] = 0x80000000;  # Bit 31 set
    $grid[0][1] = 0xFFFFFFFF;  # All bits set
    $grid[1][0] = 0xAAAAAAAA; # Alternating pattern
    
    # Test individual bit extraction (like get_cell function)
    my $bit_31_word0 = ($grid[0][0] >> 31) & 1;
    my $bit_0_word1 = ($grid[0][1] >> 0) & 1;
    my $bit_31_word1 = ($grid[0][1] >> 31) & 1;
    
    is($bit_31_word0, 1, 'Bit 31 of 0x80000000 should be 1');
    is($bit_0_word1, 1, 'Bit 0 of 0xFFFFFFFF should be 1');
    is($bit_31_word1, 1, 'Bit 31 of 0xFFFFFFFF should be 1');
    
    # Test bit setting operations (like set_cell function)
    my $test_word = 0;
    $test_word |= (1 << 31);  # Set bit 31
    is($test_word, 0x80000000, 'Setting bit 31 should create 0x80000000');
    
    # Test bit counting (like count_live_cells)
    my $count_80 = 0;
    my $temp_80 = $grid[0][0];
    while ($temp_80) {
        $count_80++;
        $temp_80 &= $temp_80 - 1;  # Brian Kernighan's algorithm
    }
    is($count_80, 1, '0x80000000 should have exactly 1 bit set');
    
    my $count_FF = 0;
    my $temp_FF = $grid[0][1];
    while ($temp_FF) {
        $count_FF++;
        $temp_FF &= $temp_FF - 1;
    }
    is($count_FF, 32, '0xFFFFFFFF should have exactly 32 bits set');
    
    my $count_AA = 0;
    my $temp_AA = $grid[1][0];
    while ($temp_AA) {
        $count_AA++;
        $temp_AA &= $temp_AA - 1;
    }
    is($count_AA, 16, '0xAAAAAAAA should have exactly 16 bits set');
    
    # Test the actual values are not clamped
    is($grid[0][0], 0x80000000, 'Grid should store 0x80000000 correctly');
    is($grid[0][1], 0xFFFFFFFF, 'Grid should store 0xFFFFFFFF correctly');
    is($grid[1][0], 0xAAAAAAAA, 'Grid should store 0xAAAAAAAA correctly');
};

# Print diagnostic information if tests fail
if ($ENV{TEST_VERBOSE}) {
    diag("Diagnostic information:");
    diag(sprintf("0x80000000 = %d (0x%08X)", 0x80000000, 0x80000000));
    diag(sprintf("0xFFFFFFFF = %d (0x%08X)", 0xFFFFFFFF, 0xFFFFFFFF));
    diag(sprintf("0xAAAAAAAA = %d (0x%08X)", 0xAAAAAAAA, 0xAAAAAAAA));
    
    my $test_or = 0 | 0x80000000;
    diag(sprintf("0 | 0x80000000 = %d (0x%08X)", $test_or, $test_or));
    
    if (0x80000000 == 0x7FFFFFFF) {
        diag("BUG DETECTED: Hex literals are being clamped to 0x7FFFFFFF!");
        diag("This indicates a problem in the hex literal parsing system.");
    }
}

done_testing();

