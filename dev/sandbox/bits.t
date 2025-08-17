#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use utf8;

# Test suite for bit-packed Conway's Game of Life
# Author: Claude (Anthropic)
# This will help identify PerlOnJava compatibility issues

binmode(STDOUT, ":utf8");

# Test constants
my $TEST_WIDTH = 64;
my $TEST_HEIGHT = 32;
my $WORDS_PER_ROW = $TEST_WIDTH / 32;

# Basic bit operation tests
subtest 'Basic Bit Operations' => sub {
    plan tests => 12;
    
    # Test basic bit shifting
    is(1 << 0, 1, "Left shift by 0");
    is(1 << 1, 2, "Left shift by 1");
    is(1 << 31, 2147483648, "Left shift by 31 (sign bit)");
    
    # Test bit masking
    my $value = 0b10101010;
    is($value & 1, 0, "Mask LSB (0)");
    is(($value >> 1) & 1, 1, "Mask bit 1 (1)");
    is(($value >> 7) & 1, 1, "Mask MSB (1)");
    
    # Test bitwise OR
    is(0 | (1 << 5), 32, "OR with bit 5");
    is(15 | (1 << 4), 31, "OR with existing bits");
    
    # Test bitwise AND NOT (clearing bits)
    is(31 & ~(1 << 4), 15, "Clear bit 4");
    is(255 & ~1, 254, "Clear LSB");
    
    # Test large numbers
    my $large = 2**31 - 1;
    is($large >> 1, 2**30 - 1, "Right shift large number");
    is($large & 1, 1, "Mask LSB of large number");
};

# Grid structure tests
subtest 'Grid Structure' => sub {
    plan tests => 8;
    
    # Test grid creation
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @grid, \@row_words;
    }
    
    is(scalar @grid, $TEST_HEIGHT, "Grid has correct height");
    is(scalar @{$grid[0]}, $WORDS_PER_ROW, "Row has correct number of words");
    is($grid[0][0], 0, "Initial cell is zero");
    is($grid[$TEST_HEIGHT-1][$WORDS_PER_ROW-1], 0, "Last cell is zero");
    
    # Test setting a word
    $grid[5][1] = 0xAAAAAAAA;  # Alternating bit pattern
    is($grid[5][1], 0xAAAAAAAA, "Word set correctly");
    is($grid[5][0], 0, "Adjacent word unchanged");
    is($grid[4][1], 0, "Adjacent row unchanged");
    is($grid[6][1], 0, "Adjacent row unchanged");
};

# Cell manipulation tests
subtest 'Cell Set/Get Operations' => sub {
    plan tests => 16;
    
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @grid, \@row_words;
    }
    
    # Test setting individual cells
    set_cell(\@grid, 0, 0, 1);    # First cell
    set_cell(\@grid, 0, 31, 1);   # Last cell in first word
    set_cell(\@grid, 0, 32, 1);   # First cell in second word
    set_cell(\@grid, 5, 10, 1);   # Random cell
    
    # Test getting those cells back
    is(get_cell(\@grid, 0, 0), 1, "Get first cell");
    is(get_cell(\@grid, 0, 31), 1, "Get bit 31");
    is(get_cell(\@grid, 0, 32), 1, "Get first cell of word 2");
    is(get_cell(\@grid, 5, 10), 1, "Get random cell");
    
    # Test adjacent cells are still zero
    is(get_cell(\@grid, 0, 1), 0, "Adjacent cell is zero");
    is(get_cell(\@grid, 1, 0), 0, "Adjacent row is zero");
    is(get_cell(\@grid, 0, 30), 0, "Cell before bit 31 is zero");
    is(get_cell(\@grid, 0, 33), 0, "Cell after bit 32 is zero");
    
    # Test boundary conditions
    is(get_cell(\@grid, -1, 0), 0, "Negative row returns 0");
    is(get_cell(\@grid, 0, -1), 0, "Negative col returns 0");
    is(get_cell(\@grid, $TEST_HEIGHT, 0), 0, "Row too large returns 0");
    is(get_cell(\@grid, 0, $TEST_WIDTH), 0, "Col too large returns 0");
    
    # Test clearing cells
    set_cell(\@grid, 0, 0, 0);
    is(get_cell(\@grid, 0, 0), 0, "Cell cleared correctly");
    is(get_cell(\@grid, 0, 31), 1, "Other cells unchanged");
    
    # Test the actual bit patterns in words
    $grid[10][0] = 0;
    set_cell(\@grid, 10, 5, 1);
    is($grid[10][0], 1 << 5, "Word contains correct bit pattern");
    
    set_cell(\@grid, 10, 7, 1);
    is($grid[10][0], (1 << 5) | (1 << 7), "Multiple bits set correctly");
};

# Pattern generation tests
subtest 'Pattern Generation' => sub {
    plan tests => 10;
    
    # Test random generation
    my @random_grid = generate_random_test();
    is(scalar @random_grid, $TEST_HEIGHT, "Random grid has correct height");
    
    my $total_bits = 0;
    my $zero_words = 0;
    for my $row (0 .. $TEST_HEIGHT - 1) {
        for my $word (0 .. $WORDS_PER_ROW - 1) {
            my $word_val = $random_grid[$row][$word];
            if ($word_val == 0) {
                $zero_words++;
            }
            # Count bits in word
            my $temp = $word_val;
            while ($temp) {
                $total_bits++;
                $temp &= $temp - 1;
            }
        }
    }
    
    ok($total_bits > 0, "Random grid has some live cells");
    ok($zero_words < $TEST_HEIGHT * $WORDS_PER_ROW, "Random grid has some non-zero words");
    
    # Test glider generation
    my @glider_grid = generate_glider_test();
    is(scalar @glider_grid, $TEST_HEIGHT, "Glider grid has correct height");
    
    # Check specific glider pattern cells
    is(get_cell(\@glider_grid, 1, 2), 1, "Glider cell (1,2)");
    is(get_cell(\@glider_grid, 2, 3), 1, "Glider cell (2,3)");
    is(get_cell(\@glider_grid, 3, 1), 1, "Glider cell (3,1)");
    is(get_cell(\@glider_grid, 3, 2), 1, "Glider cell (3,2)");
    is(get_cell(\@glider_grid, 3, 3), 1, "Glider cell (3,3)");
    is(get_cell(\@glider_grid, 0, 0), 0, "Empty cell is empty");
};

# Neighbor counting tests
subtest 'Neighbor Counting' => sub {
    plan tests => 8;
    
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @grid, \@row_words;
    }
    
    # Create a simple test pattern:
    # .#.
    # #X#  (X is the cell we're testing)
    # .#.
    set_cell(\@grid, 9, 9, 1);   # top
    set_cell(\@grid, 10, 8, 1);  # left
    set_cell(\@grid, 10, 10, 1); # right
    set_cell(\@grid, 11, 9, 1);  # bottom
    
    my $neighbors = count_neighbors_test(\@grid, 10, 9);
    is($neighbors, 4, "Count 4 neighbors correctly");
    
    # Test corner case
    set_cell(\@grid, 9, 8, 1);   # top-left
    $neighbors = count_neighbors_test(\@grid, 10, 9);
    is($neighbors, 5, "Count 5 neighbors correctly");
    
    # Test edge cell
    my @edge_grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @edge_grid, \@row_words;
    }
    
    set_cell(\@edge_grid, 0, 1, 1);  # neighbor of (0,0)
    set_cell(\@edge_grid, 1, 0, 1);  # neighbor of (0,0)
    set_cell(\@edge_grid, 1, 1, 1);  # neighbor of (0,0)
    
    $neighbors = count_neighbors_test(\@edge_grid, 0, 0);
    is($neighbors, 3, "Edge cell neighbor count");
    
    # Test bit boundary (bit 31 to bit 32)
    my @boundary_grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @boundary_grid, \@row_words;
    }
    
    set_cell(\@boundary_grid, 5, 30, 1);
    set_cell(\@boundary_grid, 5, 32, 1);
    set_cell(\@boundary_grid, 4, 31, 1);
    set_cell(\@boundary_grid, 6, 31, 1);
    
    $neighbors = count_neighbors_test(\@boundary_grid, 5, 31);
    is($neighbors, 4, "Bit boundary neighbor count");
    
    # Test empty grid
    my @empty_grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @empty_grid, \@row_words;
    }
    
    $neighbors = count_neighbors_test(\@empty_grid, 10, 10);
    is($neighbors, 0, "Empty grid has no neighbors");
    
    # Test full 3x3 around center
    my @full_grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @full_grid, \@row_words;
    }
    
    for my $dr (-1 .. 1) {
        for my $dc (-1 .. 1) {
            set_cell(\@full_grid, 10 + $dr, 10 + $dc, 1);
        }
    }
    
    $neighbors = count_neighbors_test(\@full_grid, 10, 10);
    is($neighbors, 8, "Full 3x3 has 8 neighbors");
    
    # Test asymmetric pattern
    my @asym_grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @asym_grid, \@row_words;
    }
    
    set_cell(\@asym_grid, 9, 9, 1);   # top
    set_cell(\@asym_grid, 9, 10, 1);  # top-right
    set_cell(\@asym_grid, 10, 8, 1);  # left
    
    $neighbors = count_neighbors_test(\@asym_grid, 10, 9);
    is($neighbors, 3, "Asymmetric pattern neighbor count");
    
    # Verify the test cell itself isn't counted
    set_cell(\@asym_grid, 10, 9, 1);  # Set the center cell
    $neighbors = count_neighbors_test(\@asym_grid, 10, 9);
    is($neighbors, 3, "Center cell not counted as neighbor");
};

# Game of Life rules tests
subtest 'Game of Life Rules' => sub {
    plan tests => 6;
    
    # Test Rule 1: Live cell with < 2 neighbors dies
    my @grid1 = create_empty_grid();
    set_cell(\@grid1, 10, 10, 1);  # Center cell alive
    set_cell(\@grid1, 10, 11, 1);  # One neighbor
    
    my @next1 = next_generation_test(@grid1);
    is(get_cell(\@next1, 10, 10), 0, "Live cell with 1 neighbor dies");
    
    # Test Rule 2: Live cell with 2-3 neighbors survives
    my @grid2 = create_empty_grid();
    set_cell(\@grid2, 10, 10, 1);  # Center cell alive
    set_cell(\@grid2, 10, 11, 1);  # Neighbor 1
    set_cell(\@grid2, 11, 10, 1);  # Neighbor 2
    
    my @next2 = next_generation_test(@grid2);
    is(get_cell(\@next2, 10, 10), 1, "Live cell with 2 neighbors survives");
    
    # Test Rule 3: Live cell with > 3 neighbors dies
    my @grid3 = create_empty_grid();
    set_cell(\@grid3, 10, 10, 1);  # Center cell alive
    set_cell(\@grid3, 9, 10, 1);   # North
    set_cell(\@grid3, 11, 10, 1);  # South  
    set_cell(\@grid3, 10, 9, 1);   # West
    set_cell(\@grid3, 10, 11, 1);  # East
    
    my @next3 = next_generation_test(@grid3);
    is(get_cell(\@next3, 10, 10), 0, "Live cell with 4 neighbors dies");
    
    # Test Rule 4: Dead cell with exactly 3 neighbors becomes alive
    my @grid4 = create_empty_grid();
    # Center cell (10,10) is dead
    set_cell(\@grid4, 9, 10, 1);   # North
    set_cell(\@grid4, 11, 10, 1);  # South  
    set_cell(\@grid4, 10, 9, 1);   # West
    
    my @next4 = next_generation_test(@grid4);
    is(get_cell(\@next4, 10, 10), 1, "Dead cell with 3 neighbors becomes alive");
    
    # Test oscillator (blinker)
    my @blinker = create_empty_grid();
    set_cell(\@blinker, 10, 9, 1);   # Horizontal line
    set_cell(\@blinker, 10, 10, 1);
    set_cell(\@blinker, 10, 11, 1);
    
    my @blinker_next = next_generation_test(@blinker);
    # Should become vertical
    is(get_cell(\@blinker_next, 9, 10), 1, "Blinker rotates - top");
    is(get_cell(\@blinker_next, 11, 10), 1, "Blinker rotates - bottom");
};

# Bit counting efficiency test
subtest 'Bit Counting' => sub {
    plan tests => 6;
    
    # Test Brian Kernighan's algorithm
    is(count_bits_in_word(0), 0, "Zero word has no bits");
    is(count_bits_in_word(1), 1, "Single bit");
    is(count_bits_in_word(3), 2, "Two bits");
    is(count_bits_in_word(0xFF), 8, "Byte of bits");
    is(count_bits_in_word(0xAAAAAAAA), 16, "Alternating pattern");
    is(count_bits_in_word(0xFFFFFFFF), 32, "All bits set");
};

# Edge case tests for PerlOnJava
subtest 'PerlOnJava Edge Cases' => sub {
    plan tests => 8;
    
    # Test large bit shifts
    my $large_shift = 1 << 30;
    ok($large_shift > 0, "Large left shift doesn't overflow to negative");
    
    # Test sign bit handling
    my $sign_bit = 1 << 31;
    is($sign_bit, 2147483648, "Sign bit value correct");
    
    # Test negative number handling
    my $negative = -1;
    my $masked = $negative & 0xFF;
    is($masked, 255, "Negative number masking works");
    
    # Test modulo operations
    is(63 % 32, 31, "Modulo for bit index");
    is(32 % 32, 0, "Modulo at boundary");
    
    # Test integer division
    is(int(63 / 32), 1, "Integer division for word index");
    is(int(32 / 32), 1, "Integer division at boundary");
    
    # Test array bounds
    my @test_array = (1, 2, 3);
    is($test_array[1], 2, "Array indexing works");
};

# Helper functions for tests
sub set_cell {
    my ($grid, $row, $col, $value) = @_;
    return if $row < 0 || $row >= $TEST_HEIGHT || $col < 0 || $col >= $TEST_WIDTH;
    
    my $word_idx = int($col / 32);
    my $bit_idx = $col % 32;
    
    if ($value) {
        $grid->[$row][$word_idx] |= (1 << $bit_idx);
    } else {
        $grid->[$row][$word_idx] &= ~(1 << $bit_idx);
    }
}

sub get_cell {
    my ($grid, $row, $col) = @_;
    return 0 if $row < 0 || $row >= $TEST_HEIGHT || $col < 0 || $col >= $TEST_WIDTH;
    
    my $word_idx = int($col / 32);
    my $bit_idx = $col % 32;
    
    return ($grid->[$row][$word_idx] >> $bit_idx) & 1;
}

sub count_neighbors_test {
    my ($grid, $row, $word_idx, $bit_idx) = @_;
    
    # If called with row, col instead of row, word_idx, bit_idx
    if (@_ == 3) {
        my $col = $word_idx;  # Second arg is actually col
        $word_idx = int($col / 32);
        $bit_idx = $col % 32;
    }
    
    my $col = $word_idx * 32 + $bit_idx;
    my $count = 0;
    
    # Check all 8 neighbors
    for my $dr (-1 .. 1) {
        for my $dc (-1 .. 1) {
            next if $dr == 0 && $dc == 0;  # Skip self
            my $nr = $row + $dr;
            my $nc = $col + $dc;
            $count += get_cell($grid, $nr, $nc);
        }
    }
    return $count;
}

sub generate_random_test {
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = ();
        for my $word (0 .. $WORDS_PER_ROW - 1) {
            my $bits = int(rand(2**16)) + (int(rand(2**16)) << 16);  # Safer for PerlOnJava
            push @row_words, $bits;
        }
        push @grid, \@row_words;
    }
    return @grid;
}

sub generate_glider_test {
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @grid, \@row_words;
    }
    
    # Place glider pattern
    set_cell(\@grid, 1, 2, 1);
    set_cell(\@grid, 2, 3, 1);
    set_cell(\@grid, 3, 1, 1);
    set_cell(\@grid, 3, 2, 1);
    set_cell(\@grid, 3, 3, 1);
    
    return @grid;
}

sub next_generation_test {
    my @current = @_;
    my @next = ();
    
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @next, \@row_words;
    }
    
    for my $row (0 .. $TEST_HEIGHT - 1) {
        for my $word_idx (0 .. $WORDS_PER_ROW - 1) {
            my $new_word = 0;
            
            for my $bit_idx (0 .. 31) {
                my $col = $word_idx * 32 + $bit_idx;
                last if $col >= $TEST_WIDTH;
                
                my $neighbors = count_neighbors_test(\@current, $row, $word_idx, $bit_idx);
                my $current_cell = ($current[$row][$word_idx] >> $bit_idx) & 1;
                
                my $new_cell = 0;
                if ($current_cell) {
                    $new_cell = 1 if $neighbors == 2 || $neighbors == 3;
                } else {
                    $new_cell = 1 if $neighbors == 3;
                }
                
                $new_word |= ($new_cell << $bit_idx);
            }
            
            $next[$row][$word_idx] = $new_word;
        }
    }
    
    return @next;
}

sub create_empty_grid {
    my @grid = ();
    for my $row (0 .. $TEST_HEIGHT - 1) {
        my @row_words = (0) x $WORDS_PER_ROW;
        push @grid, \@row_words;
    }
    return @grid;
}

sub count_bits_in_word {
    my $word = shift;
    my $count = 0;
    while ($word) {
        $count++;
        $word &= $word - 1;  # Clear lowest set bit
    }
    return $count;
}

# Run all tests
done_testing();

print "\n=== Test Summary ===\n";
print "If all tests pass, the bit operations should work correctly.\n";
print "If specific subtests fail, that indicates where PerlOnJava differs from standard Perl.\n";
print "Pay special attention to:\n";
print "- Bit shift operations (especially sign bit)\n";
print "- Large integer handling\n"; 
print "- Bitwise operations (&, |, ^, ~)\n";
print "- Array indexing and bounds checking\n";

