#!/usr/bin/perl
use strict;
use warnings;
use Getopt::Long;

# =============================================================================
# Bit-packed Conway's Game of Life
# Author: Claude (Anthropic)
# =============================================================================
#
# OVERVIEW:
# This implementation uses bit-packing to store 32 cells per 32-bit integer,
# reducing memory usage by 32x and enabling SIMD-style parallel operations.
#
# KEY OPTIMIZATIONS:
# 1. Bit-packing: Each integer holds 32 cells (1 bit each)
# 2. Parallel algorithm: Uses bitwise full-adder trees to count all 32
#    neighbors simultaneously (~30 ops vs 8 function calls per bit)
# 3. Braille display: 16-bit lookup table converts 4 columns at once
# 4. Lazy initialization: Lookup tables built only when needed
#
# DATA STRUCTURES:
# - 2D grid: Array of arrayrefs, each inner array holds $words_per_row integers
#   @grid[$row][$word_idx] where each word contains 32 horizontal cells
# - Flat grid: Single arrayref with $height * $words_per_row integers
#   $grid->[$row * $words_per_row + $word_idx]
#
# CELL ADDRESSING:
#   Column 0-31 are in word 0, columns 32-63 in word 1, etc.
#   Within a word: bit 0 = leftmost column, bit 31 = rightmost
#   Cell at (row, col): word_idx = col/32, bit_idx = col%32
#   Value: ($grid[$row][$word_idx] >> $bit_idx) & 1
#
# =============================================================================

use utf8;
use Time::HiRes qw(time);
binmode(STDOUT, ":utf8");

# Default values
my $width = 100;
my $height = 100;
my $generations = 5000;
my $resolution = "auto";  # auto, block, braille, none
my $pattern = "auto";     # auto, glider, random
my $algorithm = "parallel"; # scalar, parallel, flat
my $help = 0;

# Parse command line options
GetOptions(
    'width|w=i'        => \$width,
    'height|h=i'       => \$height,
    'generations|g=i'  => \$generations,
    'resolution|r=s'   => \$resolution,
    'pattern|p=s'      => \$pattern,
    'algorithm|a=s'    => \$algorithm,
    'help|?'           => \$help,
) or die("Error in command line arguments\n");

# Show help
if ($help) {
    print <<'HELP';
Bit-packed Conway's Game of Life
Author: Claude (Anthropic)

USAGE:
    perl life.pl [options]
    perl life.pl [width] [height] [generations]  # Legacy positional args

OPTIONS:
    -w, --width N          Grid width (default: 100, rounded to multiple of 32)
    -h, --height N         Grid height (default: 100)
    -g, --generations N    Number of generations to simulate (default: 5000)
    -r, --resolution MODE  Display resolution mode:
                            auto   - Choose best for grid size (default)
                            ascii  - ASCII characters (█ and ·)
                            block  - 2x2 block characters (4x resolution)
                            braille - 2x4 Braille characters (8x resolution)  
                            none   - No visual display, stats only
    -p, --pattern TYPE     Initial pattern:
                            auto   - Glider for small grids, random for large (default)
                            glider - Glider pattern with additional structures
                            random - Random initial state
    -a, --algorithm ALG    Algorithm for neighbor counting:
                            scalar   - Process each bit individually
                            parallel - SIMD-style parallel bitwise operations (default)
                            flat     - Parallel with flattened 1D array
    --help                 Show this help message

EXAMPLES:
    perl life.pl                                    # Default 100x100 grid
    perl life.pl --width 160 --height 80 -g 200    # Larger grid, more generations
    perl life.pl -w 320 -h 160 -r braille -p random # High-res Braille display
    perl life.pl 128 64 50                          # Legacy positional arguments

NOTES:
    - Width is automatically rounded up to nearest multiple of 32 for bit-packing
    - Block mode displays 2x2 cells per character (4x resolution)
    - Braille mode displays 2x4 cells per character (8x resolution)
    - Auto resolution: block for ≤160x80, braille for ≤320x160, none for larger
    - Requires UTF-8 terminal support for block and Braille characters

HELP
    exit(0);
}

# Handle legacy positional arguments (for backwards compatibility)
if (@ARGV >= 1 && $ARGV[0] =~ /^\d+$/) {
    $width = $ARGV[0];
    $height = $ARGV[1] if @ARGV >= 2 && $ARGV[1] =~ /^\d+$/;
    $generations = $ARGV[2] if @ARGV >= 3 && $ARGV[2] =~ /^\d+$/;
}

# Validate arguments
die "Width must be positive\n" if $width <= 0;
die "Height must be positive\n" if $height <= 0;
die "Generations must be positive\n" if $generations <= 0;
die "Invalid resolution mode: $resolution\nValid options: auto, ascii, block, braille, none\n" unless $resolution =~ /^(auto|ascii|block|braille|none)$/;
die "Invalid pattern type: $pattern\nValid options: auto, glider, random\n" unless $pattern =~ /^(auto|glider|random)$/;
die "Invalid algorithm: $algorithm\nValid options: scalar, parallel, flat\n" unless $algorithm =~ /^(scalar|parallel|flat)$/;

# Ensure width is multiple of 32 for clean bit packing
$width = int(($width + 31) / 32) * 32;
my $words_per_row = $width / 32;

# Determine display mode
my $display_mode;
if ($resolution eq "auto") {
    if ($width <= 80 && $height <= 40) {
        $display_mode = "ascii";   # Safe ASCII fallback for small grids
    } elsif ($width <= 160 && $height <= 80) {
        $display_mode = "block";   # 2x2 block characters
    } elsif ($width <= 320 && $height <= 160) {
        $display_mode = "braille"; # 2x4 Braille characters (8x resolution!)
    } else {
        $display_mode = "none";
    }
} else {
    $display_mode = $resolution;
}

# Determine pattern type  
my $pattern_type;
if ($pattern eq "auto") {
    if ($width <= 160 && $height <= 80) {
        $pattern_type = "glider";
    } else {
        $pattern_type = "random";
    }
} else {
    $pattern_type = $pattern;
}

print "Bit-packed Conway's Game of Life\n";
print "Author: Claude (Anthropic)\n\n";
print "Grid: ${width}x${height} ($words_per_row words per row)\n";
print "Generations: $generations\n";
print "Pattern: $pattern_type\n";
print "Display: $display_mode\n";
print "Algorithm: $algorithm\n";

# Test UTF-8 support and warn if needed
if ($display_mode eq "block" || $display_mode eq "braille") {
    # Test if UTF-8 is working properly
    print "UTF-8 test: ";
    if ($display_mode eq "block") {
        print "▀▄▌▐ ";
    } else {
        print "⠁⠃⠉⠙ ";
    }
    print "(if you see strange characters, use --resolution ascii)\n";
}
print "\n";

# =============================================================================
# PATTERN GENERATORS
# =============================================================================
# Create initial grid states. Each function has 2D and flat variants.
# Random: fills grid with random bits (approximately 50% density)
# Glider: creates two classic glider patterns for visual testing
# =============================================================================

sub generate_random {
    my @grid = ();
    for my $row (0 .. $height - 1) {
        my @row_words = ();
        for my $word (0 .. $words_per_row - 1) {
            my $bits = int(rand(2**32));
            push @row_words, $bits;
        }
        push @grid, \@row_words;
    }
    return @grid;
}

sub generate_random_flat {
    my @grid = ();
    for my $i (0 .. $height * $words_per_row - 1) {
        push @grid, int(rand(2**32));
    }
    return \@grid;
}

sub generate_glider {
    my @grid = ();
    for my $row (0 .. $height - 1) {
        my @row_words = (0) x $words_per_row;
        push @grid, \@row_words;
    }
    
    set_cell(\@grid, 1, 2, 1);
    set_cell(\@grid, 2, 3, 1);
    set_cell(\@grid, 3, 1, 1);
    set_cell(\@grid, 3, 2, 1);
    set_cell(\@grid, 3, 3, 1);
    
    set_cell(\@grid, 10, 10, 1);
    set_cell(\@grid, 11, 12, 1);
    set_cell(\@grid, 12, 10, 1);
    set_cell(\@grid, 12, 11, 1);
    set_cell(\@grid, 12, 12, 1);
    
    return @grid;
}

sub generate_glider_flat {
    my @grid = (0) x ($height * $words_per_row);
    
    set_cell_flat(\@grid, 1, 2, 1);
    set_cell_flat(\@grid, 2, 3, 1);
    set_cell_flat(\@grid, 3, 1, 1);
    set_cell_flat(\@grid, 3, 2, 1);
    set_cell_flat(\@grid, 3, 3, 1);
    
    set_cell_flat(\@grid, 10, 10, 1);
    set_cell_flat(\@grid, 11, 12, 1);
    set_cell_flat(\@grid, 12, 10, 1);
    set_cell_flat(\@grid, 12, 11, 1);
    set_cell_flat(\@grid, 12, 12, 1);
    
    return \@grid;
}

# =============================================================================
# CELL ACCESSORS
# =============================================================================
# Low-level bit manipulation for individual cells.
# Used by pattern generators and slow display modes (ASCII, block).
# The fast algorithms bypass these for direct word-level operations.
# =============================================================================

sub set_cell {
    my ($grid, $row, $col, $value) = @_;
    return if $row < 0 || $row >= $height || $col < 0 || $col >= $width;
    
    my $word_idx = int($col / 32);
    my $bit_idx = $col % 32;
    
    if ($value) {
        $grid->[$row][$word_idx] |= (1 << $bit_idx);
    } else {
        $grid->[$row][$word_idx] &= ~(1 << $bit_idx);
    }
}

sub set_cell_flat {
    my ($grid, $row, $col, $value) = @_;
    return if $row < 0 || $row >= $height || $col < 0 || $col >= $width;
    
    my $idx = $row * $words_per_row + int($col / 32);
    my $bit_idx = $col % 32;
    
    if ($value) {
        $grid->[$idx] |= (1 << $bit_idx);
    } else {
        $grid->[$idx] &= ~(1 << $bit_idx);
    }
}

sub get_cell {
    my ($grid, $row, $col) = @_;
    return 0 if $row < 0 || $row >= $height || $col < 0 || $col >= $width;
    
    my $word_idx = int($col / 32);
    my $bit_idx = $col % 32;
    
    return ($grid->[$row][$word_idx] >> $bit_idx) & 1;
}

# =============================================================================
# ALGORITHM: SCALAR (baseline)
# =============================================================================
# Processes each of the 32 bits in a word individually.
# For each cell, counts its 8 neighbors using bit shifts and masks.
# This is the simplest approach but still faster than per-cell function calls
# because we pre-fetch neighboring words and use inline bit operations.
#
# Time complexity: O(height * width) with 8 neighbor checks per cell
# =============================================================================
sub next_generation_scalar {
    my @current = @_;
    my @next = ();
    
    # Initialize next generation grid
    for my $row (0 .. $height - 1) {
        my @row_words = (0) x $words_per_row;
        push @next, \@row_words;
    }
    
    # Process each cell - optimized with inline neighbor counting
    for my $row (0 .. $height - 1) {
        # Get row references to avoid repeated dereferencing
        my $curr_row = $current[$row];
        my $above_row = $row > 0 ? $current[$row - 1] : undef;
        my $below_row = $row < $height - 1 ? $current[$row + 1] : undef;
        
        for my $word_idx (0 .. $words_per_row - 1) {
            my $new_word = 0;
            my $curr_word = $curr_row->[$word_idx];
            
            # Get adjacent words for cross-boundary bit access
            my $left_word = $word_idx > 0 ? $curr_row->[$word_idx - 1] : 0;
            my $right_word = $word_idx < $words_per_row - 1 ? $curr_row->[$word_idx + 1] : 0;
            
            my ($above_word, $above_left, $above_right) = (0, 0, 0);
            if ($above_row) {
                $above_word = $above_row->[$word_idx];
                $above_left = $word_idx > 0 ? $above_row->[$word_idx - 1] : 0;
                $above_right = $word_idx < $words_per_row - 1 ? $above_row->[$word_idx + 1] : 0;
            }
            
            my ($below_word, $below_left, $below_right) = (0, 0, 0);
            if ($below_row) {
                $below_word = $below_row->[$word_idx];
                $below_left = $word_idx > 0 ? $below_row->[$word_idx - 1] : 0;
                $below_right = $word_idx < $words_per_row - 1 ? $below_row->[$word_idx + 1] : 0;
            }
            
            # Process each bit in the word
            for my $bit_idx (0 .. 31) {
                # Count neighbors inline
                my $count = 0;
                
                # Same row: left and right
                if ($bit_idx > 0) {
                    $count += ($curr_word >> ($bit_idx - 1)) & 1;
                } elsif ($word_idx > 0) {
                    $count += ($left_word >> 31) & 1;
                }
                if ($bit_idx < 31) {
                    $count += ($curr_word >> ($bit_idx + 1)) & 1;
                } elsif ($word_idx < $words_per_row - 1) {
                    $count += $right_word & 1;
                }
                
                # Row above
                if ($above_row) {
                    $count += ($above_word >> $bit_idx) & 1;  # directly above
                    if ($bit_idx > 0) {
                        $count += ($above_word >> ($bit_idx - 1)) & 1;
                    } elsif ($word_idx > 0) {
                        $count += ($above_left >> 31) & 1;
                    }
                    if ($bit_idx < 31) {
                        $count += ($above_word >> ($bit_idx + 1)) & 1;
                    } elsif ($word_idx < $words_per_row - 1) {
                        $count += $above_right & 1;
                    }
                }
                
                # Row below
                if ($below_row) {
                    $count += ($below_word >> $bit_idx) & 1;  # directly below
                    if ($bit_idx > 0) {
                        $count += ($below_word >> ($bit_idx - 1)) & 1;
                    } elsif ($word_idx > 0) {
                        $count += ($below_left >> 31) & 1;
                    }
                    if ($bit_idx < 31) {
                        $count += ($below_word >> ($bit_idx + 1)) & 1;
                    } elsif ($word_idx < $words_per_row - 1) {
                        $count += $below_right & 1;
                    }
                }
                
                my $current_cell = ($curr_word >> $bit_idx) & 1;
                
                # Conway's rules: survive with 2-3, birth with 3
                my $new_cell = ($count == 3) || ($current_cell && $count == 2) ? 1 : 0;
                $new_word |= ($new_cell << $bit_idx);
            }
            
            $next[$row][$word_idx] = $new_word;
        }
    }
    
    return @next;
}

# =============================================================================
# ALGORITHM: PARALLEL (SIMD-style bitwise operations)
# =============================================================================
# This is the key optimization. Instead of processing cells one at a time,
# we process all 32 cells in a word simultaneously using bitwise operations.
#
# THE INSIGHT:
# Each bit position i in a word has 8 neighbors at the same bit position i
# in 8 different "neighbor words". We can compute all 32 neighbor counts
# in parallel using a full-adder tree.
#
# NEIGHBOR WORDS (for word at position [row][word_idx]):
#   above_left   above   above_right   <- row-1, shifted right/center/left
#   n_left       [cell]  n_right       <- row,   shifted right/left
#   below_left   below   below_right   <- row+1, shifted right/center/left
#
# FULL-ADDER TREE:
# A full adder computes: sum = a^b^c, carry = (a&b)|(b&c)|(a&c)
# We use this to sum 8 single-bit values into a 4-bit count (0-8).
# The tree structure:
#   Level 1: Add triplets (above_left, above, above_right) -> s1, c1
#            Add triplets (n_left, n_right, below_left) -> s2, c2
#   Level 2: Add (s1, s2, below) -> s3, c3
#   Level 3: Add (s3, below_right) -> sum0, c4
#   Then combine carries to get sum1, sum2, sum3 (the 4-bit count)
#
# CONWAY'S RULES (applied in parallel to all 32 cells):
#   - Cell survives if alive AND neighbors == 2
#   - Cell is born if neighbors == 3
#   - Combined: new = (count==3) OR (cell AND count==2)
#   - In binary: new = ~sum3 & ~sum2 & sum1 & (sum0 | cell)
#
# Time complexity: O(height * words_per_row) with ~30 bitwise ops per word
# =============================================================================
sub next_generation_parallel {
    my @current = @_;
    my @next = ();
    
    # Initialize next generation grid
    for my $row (0 .. $height - 1) {
        my @row_words = (0) x $words_per_row;
        push @next, \@row_words;
    }
    
    # Process each row
    for my $row (0 .. $height - 1) {
        my $curr_row = $current[$row];
        my $above_row = $row > 0 ? $current[$row - 1] : undef;
        my $below_row = $row < $height - 1 ? $current[$row + 1] : undef;
        
        for my $word_idx (0 .. $words_per_row - 1) {
            my $cell = $curr_row->[$word_idx];
            
            # Get neighbor words for current row
            my $curr_left = $word_idx > 0 ? $curr_row->[$word_idx - 1] : 0;
            my $curr_right = $word_idx < $words_per_row - 1 ? $curr_row->[$word_idx + 1] : 0;
            
            # Compute left and right neighbors for current row (all 32 bits at once)
            # n_left: shift cell right by 1, fill bit 0 from high bit of left word
            # n_right: shift cell left by 1, fill bit 31 from low bit of right word
            my $n_left = (($cell >> 1) & 0x7FFFFFFF) | (($curr_left & 0x80000000) ? 0x00000001 : 0);
            my $n_right = (($cell << 1) & 0xFFFFFFFE) | (($curr_right & 0x00000001) ? 0x80000000 : 0);
            
            # Get neighbor words for row above
            my ($above, $above_left_w, $above_right_w) = (0, 0, 0);
            if ($above_row) {
                $above = $above_row->[$word_idx];
                $above_left_w = $word_idx > 0 ? $above_row->[$word_idx - 1] : 0;
                $above_right_w = $word_idx < $words_per_row - 1 ? $above_row->[$word_idx + 1] : 0;
            }
            
            # Get neighbor words for row below
            my ($below, $below_left_w, $below_right_w) = (0, 0, 0);
            if ($below_row) {
                $below = $below_row->[$word_idx];
                $below_left_w = $word_idx > 0 ? $below_row->[$word_idx - 1] : 0;
                $below_right_w = $word_idx < $words_per_row - 1 ? $below_row->[$word_idx + 1] : 0;
            }
            
            # Compute shifted versions for above row
            my $above_left = (($above >> 1) & 0x7FFFFFFF) | (($above_left_w & 0x80000000) ? 0x00000001 : 0);
            my $above_right = (($above << 1) & 0xFFFFFFFE) | (($above_right_w & 0x00000001) ? 0x80000000 : 0);
            
            # Compute shifted versions for below row
            my $below_left = (($below >> 1) & 0x7FFFFFFF) | (($below_left_w & 0x80000000) ? 0x00000001 : 0);
            my $below_right = (($below << 1) & 0xFFFFFFFE) | (($below_right_w & 0x00000001) ? 0x80000000 : 0);
            
            # Now we have 8 neighbor bit-planes (each bit position has its 8 neighbors)
            # n1=above_left, n2=above, n3=above_right
            # n4=n_left,              n5=n_right
            # n6=below_left, n7=below, n8=below_right
            
            # Use parallel full adders to sum all 8 neighbors into 4-bit counts
            # Full adder: sum = a^b^c, carry = (a&b)|(b&c)|(a&c)
            
            # First level: add triplets
            my $s1 = $above_left ^ $above ^ $above_right;
            my $c1 = ($above_left & $above) | ($above & $above_right) | ($above_left & $above_right);
            
            my $s2 = $n_left ^ $n_right ^ $below_left;
            my $c2 = ($n_left & $n_right) | ($n_right & $below_left) | ($n_left & $below_left);
            
            # Second level: add s1, s2, below
            my $s3 = $s1 ^ $s2 ^ $below;
            my $c3 = ($s1 & $s2) | ($s2 & $below) | ($s1 & $below);
            
            # Third level: add s3, below_right -> gives sum bit 0 and carry
            my $sum0 = $s3 ^ $below_right;
            my $c4 = $s3 & $below_right;
            
            # Now add the carries: c1, c2, c3, c4 to get bits 1,2,3 of count
            # Add c1 + c2
            my $cc1_sum = $c1 ^ $c2;
            my $cc1_carry = $c1 & $c2;
            
            # Add cc1_sum + c3
            my $cc2_sum = $cc1_sum ^ $c3;
            my $cc2_carry = $cc1_sum & $c3;
            
            # Add cc2_sum + c4 -> this is sum bit 1
            my $sum1 = $cc2_sum ^ $c4;
            my $c5 = $cc2_sum & $c4;
            
            # Add carries for bit 2: cc1_carry + cc2_carry + c5
            my $sum2 = $cc1_carry ^ $cc2_carry ^ $c5;
            my $c6 = ($cc1_carry & $cc2_carry) | ($cc2_carry & $c5) | ($cc1_carry & $c5);
            
            # Bit 3 is just c6 (overflow indicator)
            my $sum3 = $c6;
            
            # Apply Conway's rules in parallel:
            # Alive if (count == 3) OR (cell AND count == 2)
            # count == 2: sum3=0, sum2=0, sum1=1, sum0=0
            # count == 3: sum3=0, sum2=0, sum1=1, sum0=1
            # So: new = ~sum3 & ~sum2 & sum1 & (sum0 | cell)
            my $not_sum3 = ~$sum3;
            my $not_sum2 = ~$sum2;
            my $new_word = $not_sum3 & $not_sum2 & $sum1 & ($sum0 | $cell);
            
            $next[$row][$word_idx] = $new_word & 0xFFFFFFFF;
        }
    }
    
    return @next;
}

# =============================================================================
# ALGORITHM: FLAT (parallel algorithm with 1D array)
# =============================================================================
# Same parallel full-adder algorithm as above, but uses a flat 1D array
# instead of 2D array-of-arrays. This avoids Perl's array dereference
# overhead and may improve cache locality.
#
# Array layout: $grid->[$row * $words_per_row + $word_idx]
# =============================================================================
sub next_generation_flat {
    my ($curr) = @_;
    my @next_flat = (0) x ($height * $words_per_row);
    
    for my $row (0 .. $height - 1) {
        my $row_offset = $row * $words_per_row;
        my $above_offset = ($row - 1) * $words_per_row;
        my $below_offset = ($row + 1) * $words_per_row;
        
        for my $word_idx (0 .. $words_per_row - 1) {
            my $idx = $row_offset + $word_idx;
            my $cell = $curr->[$idx];
            
            my $curr_left = $word_idx > 0 ? $curr->[$idx - 1] : 0;
            my $curr_right = $word_idx < $words_per_row - 1 ? $curr->[$idx + 1] : 0;
            
            my $n_left = (($cell >> 1) & 0x7FFFFFFF) | (($curr_left & 0x80000000) ? 0x00000001 : 0);
            my $n_right = (($cell << 1) & 0xFFFFFFFE) | (($curr_right & 0x00000001) ? 0x80000000 : 0);
            
            my ($above, $above_left_w, $above_right_w) = (0, 0, 0);
            if ($row > 0) {
                my $above_idx = $above_offset + $word_idx;
                $above = $curr->[$above_idx];
                $above_left_w = $word_idx > 0 ? $curr->[$above_idx - 1] : 0;
                $above_right_w = $word_idx < $words_per_row - 1 ? $curr->[$above_idx + 1] : 0;
            }
            
            my ($below, $below_left_w, $below_right_w) = (0, 0, 0);
            if ($row < $height - 1) {
                my $below_idx = $below_offset + $word_idx;
                $below = $curr->[$below_idx];
                $below_left_w = $word_idx > 0 ? $curr->[$below_idx - 1] : 0;
                $below_right_w = $word_idx < $words_per_row - 1 ? $curr->[$below_idx + 1] : 0;
            }
            
            my $above_left = (($above >> 1) & 0x7FFFFFFF) | (($above_left_w & 0x80000000) ? 0x00000001 : 0);
            my $above_right = (($above << 1) & 0xFFFFFFFE) | (($above_right_w & 0x00000001) ? 0x80000000 : 0);
            my $below_left = (($below >> 1) & 0x7FFFFFFF) | (($below_left_w & 0x80000000) ? 0x00000001 : 0);
            my $below_right = (($below << 1) & 0xFFFFFFFE) | (($below_right_w & 0x00000001) ? 0x80000000 : 0);
            
            my $s1 = $above_left ^ $above ^ $above_right;
            my $c1 = ($above_left & $above) | ($above & $above_right) | ($above_left & $above_right);
            my $s2 = $n_left ^ $n_right ^ $below_left;
            my $c2 = ($n_left & $n_right) | ($n_right & $below_left) | ($n_left & $below_left);
            my $s3 = $s1 ^ $s2 ^ $below;
            my $c3 = ($s1 & $s2) | ($s2 & $below) | ($s1 & $below);
            my $sum0 = $s3 ^ $below_right;
            my $c4 = $s3 & $below_right;
            my $cc1_sum = $c1 ^ $c2;
            my $cc1_carry = $c1 & $c2;
            my $cc2_sum = $cc1_sum ^ $c3;
            my $cc2_carry = $cc1_sum & $c3;
            my $sum1 = $cc2_sum ^ $c4;
            my $c5 = $cc2_sum & $c4;
            my $sum2 = $cc1_carry ^ $cc2_carry ^ $c5;
            my $c6 = ($cc1_carry & $cc2_carry) | ($cc2_carry & $c5) | ($cc1_carry & $c5);
            my $sum3 = $c6;
            
            my $new_word = (~$sum3) & (~$sum2) & $sum1 & ($sum0 | $cell);
            $next_flat[$idx] = $new_word & 0xFFFFFFFF;
        }
    }
    
    return \@next_flat;
}

# Dispatcher for algorithm selection (2D grids only; flat uses direct calls)
sub next_generation {
    if ($algorithm eq 'parallel') {
        return next_generation_parallel(@_);
    } elsif ($algorithm eq 'flat') {
        return next_generation_flat(@_);
    } else {
        return next_generation_scalar(@_);
    }
}

# =============================================================================
# DISPLAY: ASCII AND BLOCK MODES
# =============================================================================
# ASCII: One character per cell (█ or ·). Simple but low density.
# Block: 2x2 cells per character using Unicode block elements. 4x density.
# Both use get_cell() which is slow but acceptable for small grids.
# =============================================================================

sub print_grid_ascii {
    my @grid = @_;
    my $total_cells = 0;
    
    print "\e[H\e[2J";  # Clear screen and move cursor to top
    
    for my $row (0 .. $height - 1) {
        for my $col (0 .. $width - 1) {
            my $cell = get_cell(\@grid, $row, $col);
            print $cell ? "█" : "·";
            $total_cells += $cell;
        }
        print "\n";
    }
    print "\nLive cells: $total_cells\n";
}

sub print_grid {
    my @grid = @_;
    my $total_cells = 0;
    
    print "\e[H\e[2J";
    
    # 2x2 block character lookup: index = (top-left) | (top-right<<1) | (bottom-left<<2) | (bottom-right<<3)
    my @block_chars = (
        ' ',    # 0000 - no cells
        '▘',    # 0001 - top-left
        '▝',    # 0010 - top-right  
        '▀',    # 0011 - top row
        '▖',    # 0100 - bottom-left
        '▌',    # 0101 - left column
        '▞',    # 0110 - diagonal /
        '▛',    # 0111 - missing bottom-right
        '▗',    # 1000 - bottom-right
        '▚',    # 1001 - diagonal \
        '▐',    # 1010 - right column
        '▜',    # 1011 - missing bottom-left
        '▄',    # 1100 - bottom row
        '▙',    # 1101 - missing top-right
        '▟',    # 1110 - missing top-left
        '█'     # 1111 - all cells
    );
    
    for (my $row = 0; $row < $height; $row += 2) {
        for (my $col = 0; $col < $width; $col += 2) {
            # Get 2x2 block of cells
            my $tl = get_cell(\@grid, $row, $col);           # top-left
            my $tr = get_cell(\@grid, $row, $col + 1);       # top-right
            my $bl = get_cell(\@grid, $row + 1, $col);       # bottom-left
            my $br = get_cell(\@grid, $row + 1, $col + 1);   # bottom-right
            
            # Create bit pattern (top-left is LSB)
            my $pattern = $tl + ($tr << 1) + ($bl << 2) + ($br << 3);
            print $block_chars[$pattern];
            
            $total_cells += $tl + $tr + $bl + $br;
        }
        print "\n";
    }
    print "\nLive cells: $total_cells\n";
}

# =============================================================================
# DISPLAY: BRAILLE LOOKUP TABLES
# =============================================================================
# Unicode Braille characters (U+2800-U+28FF) encode 8 dots in a 2x4 pattern:
#   dot1 dot4     bit0 bit3
#   dot2 dot5  =  bit1 bit4
#   dot3 dot6     bit2 bit5
#   dot7 dot8     bit6 bit7
#
# Each braille character represents 8 cells (2 columns x 4 rows), giving
# 8x display density compared to ASCII.
#
# @BRAILLE[0-255]: Maps 8-bit pattern to Unicode braille character
# @POPCOUNT[0-255]: Number of 1-bits in each byte (for cell counting)
# =============================================================================
my @BRAILLE = map { chr(0x2800 + $_) } (0..255);
my @POPCOUNT = map { my $n=$_; my $c=0; while($n) { $c++; $n &= $n-1; } $c } (0..255);

# =============================================================================
# OPTIMIZATION: 16-bit combined lookup table (lazy initialized)
# =============================================================================
# Instead of computing one braille character at a time (2 columns),
# we process 4 columns at once, outputting 2 braille characters.
#
# Index encoding (16 bits):
#   bits 0-3:   4 cells from row 0 (columns 0,1,2,3)
#   bits 4-7:   4 cells from row 1
#   bits 8-11:  4 cells from row 2
#   bits 12-15: 4 cells from row 3
#
# Output: [braille_char_1, braille_char_2, total_popcount]
#   char_1 covers columns 0-1, char_2 covers columns 2-3
#
# This reduces per-character operations and speeds up display by ~2x.
# Table is 64K entries but only built if braille display is actually used.
# =============================================================================
my @BRAILLE_PAIR;
my $braille_pair_built = 0;

sub init_braille_pair {
    return if $braille_pair_built;
    for my $idx (0..65535) {
        # Extract 4 bits from each row
        my $r0 = $idx & 0xF;
        my $r1 = ($idx >> 4) & 0xF;
        my $r2 = ($idx >> 8) & 0xF;
        my $r3 = ($idx >> 12) & 0xF;
        
        # First braille char (columns 0,1): remap bits to braille dot pattern
        my $p1 = ($r0 & 1) | (($r1 & 1) << 1) | (($r2 & 1) << 2) |
                 (($r0 & 2) << 2) | (($r1 & 2) << 3) | (($r2 & 2) << 4) |
                 (($r3 & 1) << 6) | (($r3 & 2) << 6);
        
        # Second braille char (columns 2,3): same pattern from upper 2 bits
        my $p2 = (($r0 >> 2) & 1) | ((($r1 >> 2) & 1) << 1) | ((($r2 >> 2) & 1) << 2) |
                 ((($r0 >> 2) & 2) << 2) | ((($r1 >> 2) & 2) << 3) | ((($r2 >> 2) & 2) << 4) |
                 ((($r3 >> 2) & 1) << 6) | ((($r3 >> 2) & 2) << 6);
        
        $BRAILLE_PAIR[$idx] = [$BRAILLE[$p1], $BRAILLE[$p2], $POPCOUNT[$p1] + $POPCOUNT[$p2]];
    }
    $braille_pair_built = 1;
}

# Braille display using 16-bit lookup (2D grid version)
# Processes 4 rows at a time (one braille row), 4 columns at a time (2 chars)
sub print_grid_braille {
    init_braille_pair();
    my @grid = @_;
    my $total_cells = 0;
    my $output = "\e[H\e[2J";  # ANSI: clear screen, cursor to home
    
    # Process 4 grid rows per display row (braille is 4 dots tall)
    for (my $row = 0; $row < $height; $row += 4) {
        my $r0 = $grid[$row] // [];
        my $r1 = $row + 1 < $height ? $grid[$row + 1] : [];
        my $r2 = $row + 2 < $height ? $grid[$row + 2] : [];
        my $r3 = $row + 3 < $height ? $grid[$row + 3] : [];
        
        for my $word_idx (0 .. $words_per_row - 1) {
            my $w0 = $r0->[$word_idx] // 0;
            my $w1 = $r1->[$word_idx] // 0;
            my $w2 = $r2->[$word_idx] // 0;
            my $w3 = $r3->[$word_idx] // 0;
            
            # Process 4 columns at a time -> 2 braille characters
            # Inner loop: 32 bits / 4 bits = 8 iterations = 16 braille chars per word
            for (my $bit = 0; $bit < 32; $bit += 4) {
                # Pack 4 bits from each row into 16-bit index
                my $idx = (($w0 >> $bit) & 0xF) |
                         ((($w1 >> $bit) & 0xF) << 4) |
                         ((($w2 >> $bit) & 0xF) << 8) |
                         ((($w3 >> $bit) & 0xF) << 12);
                
                my $entry = $BRAILLE_PAIR[$idx];
                $output .= $entry->[0] . $entry->[1];
                $total_cells += $entry->[2];
            }
        }
        $output .= "\n";
    }
    print $output;
    print "\nLive cells: $total_cells\n";
}

# =============================================================================
# CELL COUNTING: Brian Kernighan's algorithm
# =============================================================================
# Counts set bits using: word &= word - 1 (clears lowest set bit)
# This is O(number of 1-bits) rather than O(32) for each word.
# =============================================================================
sub count_live_cells {
    my @grid = @_;
    my $total = 0;
    
    for my $row (0 .. $height - 1) {
        for my $word_idx (0 .. $words_per_row - 1) {
            my $word = $grid[$row][$word_idx];
            while ($word) {
                $total++;
                $word &= $word - 1;
            }
        }
    }
    return $total;
}

sub count_live_cells_flat {
    my ($grid) = @_;
    my $total = 0;
    for my $i (0 .. $#$grid) {
        my $word = $grid->[$i];  # Copy to avoid modifying original
        while ($word) {
            $total++;
            $word &= $word - 1;
        }
    }
    return $total;
}

# Braille display for flat 1D arrays (same algorithm as print_grid_braille)
# Uses row offsets instead of 2D array indexing
sub print_grid_braille_flat {
    init_braille_pair();
    my ($grid) = @_;
    my $total_cells = 0;
    my $output = "\e[H\e[2J";
    
    # Same as print_grid_braille but computes row offsets for flat array access
    for (my $row = 0; $row < $height; $row += 4) {
        my $r0_off = $row * $words_per_row;
        my $r1_off = ($row + 1) * $words_per_row;
        my $r2_off = ($row + 2) * $words_per_row;
        my $r3_off = ($row + 3) * $words_per_row;
        
        for my $word_idx (0 .. $words_per_row - 1) {
            my $w0 = $grid->[$r0_off + $word_idx] // 0;
            my $w1 = $row + 1 < $height ? ($grid->[$r1_off + $word_idx] // 0) : 0;
            my $w2 = $row + 2 < $height ? ($grid->[$r2_off + $word_idx] // 0) : 0;
            my $w3 = $row + 3 < $height ? ($grid->[$r3_off + $word_idx] // 0) : 0;
            
            for (my $bit = 0; $bit < 32; $bit += 4) {
                my $idx = (($w0 >> $bit) & 0xF) |
                         ((($w1 >> $bit) & 0xF) << 4) |
                         ((($w2 >> $bit) & 0xF) << 8) |
                         ((($w3 >> $bit) & 0xF) << 12);
                
                my $entry = $BRAILLE_PAIR[$idx];
                $output .= $entry->[0] . $entry->[1];
                $total_cells += $entry->[2];
            }
        }
        $output .= "\n";
    }
    print $output;
    print "\nLive cells: $total_cells\n";
}

# =============================================================================
# MAIN SIMULATION LOOP
# =============================================================================
my @grid;      # 2D grid: @grid[$row][$word_idx]
my $grid_flat; # Flat grid: $grid_flat->[$row * $words_per_row + $word_idx]

# Choose initial pattern based on settings
if ($algorithm eq 'flat') {
    if ($pattern_type eq "glider") {
        print "Creating glider pattern (flat)...\n";
        $grid_flat = generate_glider_flat();
    } else {
        print "Generating random pattern (flat)...\n";
        $grid_flat = generate_random_flat();
    }
} else {
    if ($pattern_type eq "glider") {
        print "Creating glider pattern...\n";
        @grid = generate_glider();
    } else {
        print "Generating random pattern...\n";
        @grid = generate_random();
    }
}

my $start_time = time();

if ($display_mode ne "none") {
    if ($algorithm eq 'flat') {
        print_grid_braille_flat($grid_flat) if $display_mode eq "braille";
    } else {
        if ($display_mode eq "braille") {
            print_grid_braille(@grid);
        } elsif ($display_mode eq "ascii") {
            print_grid_ascii(@grid);
        } else {
            print_grid(@grid);
        }
    }
    sleep(1);
}

# Run simulation
for my $gen (1 .. $generations) {
    if ($algorithm eq 'flat') {
        $grid_flat = next_generation_flat($grid_flat);
        
        if ($display_mode ne "none") {
            print_grid_braille_flat($grid_flat) if $display_mode eq "braille";
            print "Generation: $gen\n";
        } elsif ($gen % 10 == 0) {
            my $live_cells = count_live_cells_flat($grid_flat);
            print "Generation $gen: $live_cells live cells\n";
        }
    } else {
        @grid = next_generation(@grid);
        
        if ($display_mode ne "none") {
            if ($display_mode eq "braille") {
                print_grid_braille(@grid);
            } elsif ($display_mode eq "ascii") {
                print_grid_ascii(@grid);
            } else {
                print_grid(@grid);
            }
            print "Generation: $gen\n";
        } elsif ($gen % 10 == 0) {
            my $live_cells = count_live_cells(@grid);
            print "Generation $gen: $live_cells live cells\n";
        }
    }
}

my $end_time = time();
my $duration = $end_time - $start_time;
$duration = 0.001 if $duration < 0.001;
my $final_count = $algorithm eq 'flat' ? count_live_cells_flat($grid_flat) : count_live_cells(@grid);

print "\nSimulation completed!\n";
print "Generations: $generations\n";
print "Final live cells: $final_count\n";
print "Time elapsed: " . sprintf("%.3f", $duration) . " seconds\n";
print "Generations per second: " . sprintf("%.2f", $generations / $duration) . "\n";

my $cells_per_sec = ($generations * $width * $height) / $duration;
my $cells_formatted;
if ($cells_per_sec >= 1e9) {
    $cells_formatted = sprintf("%.2f G", $cells_per_sec / 1e9);
} elsif ($cells_per_sec >= 1e6) {
    $cells_formatted = sprintf("%.2f M", $cells_per_sec / 1e6);
} elsif ($cells_per_sec >= 1e3) {
    $cells_formatted = sprintf("%.2f K", $cells_per_sec / 1e3);
} else {
    $cells_formatted = sprintf("%.0f", $cells_per_sec);
}
print "Cell updates per second: ${cells_formatted}cells/s\n";

# Performance comparison note
print "\nOptimization notes:\n";
print "- Using bit-packed representation (32 cells per integer)\n";
print "- Memory usage: ~" . sprintf("%.1f", ($height * $words_per_row * 4 / 1024)) . " KB\n";
print "- vs original: ~" . sprintf("%.1f", ($height * $width * 8 / 1024)) . " KB (estimated)\n";
print "- Display resolution: ";
if ($display_mode eq "braille") {
    print "8x (Braille: 2x4 cells per character)\n";
} elsif ($display_mode eq "block") {
    print "4x (Block: 2x2 cells per character)\n";
} elsif ($display_mode eq "ascii") {
    print "1x (ASCII: 1 cell per character)\n";
} else {
    print "Grid too large for visual display\n";
}

