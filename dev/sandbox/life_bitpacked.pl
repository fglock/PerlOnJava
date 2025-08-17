#!/usr/bin/perl
use strict;
use warnings;

# Bit-packed Conway's Game of Life
# Uses 32-bit integers to store 32 cells per integer for memory efficiency
# and faster operations through bitwise manipulation

# Set default values for input parameters
my $width = $ARGV[0] || 64;        # Default width (should be multiple of 32)
my $height = $ARGV[1] || 32;       # Default height
my $generations = $ARGV[2] || 100;  # Default generations
my $random = $ARGV[3];             # Use random pattern

# Ensure width is multiple of 32 for clean bit packing
$width = int(($width + 31) / 32) * 32;
my $words_per_row = $width / 32;

print "Running ${width}x${height} grid for $generations generations\n";
print "Using $words_per_row 32-bit words per row\n\n";

# Generate random initial state
sub generate_random {
    my @grid = ();
    for my $row (0 .. $height - 1) {
        my @row_words = ();
        for my $word (0 .. $words_per_row - 1) {
            # Generate random 32-bit pattern
            my $bits = int(rand(2**32));
            push @row_words, $bits;
        }
        push @grid, \@row_words;
    }
    return @grid;
}

# Create a glider pattern for testing
sub generate_glider {
    my @grid = ();
    # Initialize empty grid
    for my $row (0 .. $height - 1) {
        my @row_words = (0) x $words_per_row;
        push @grid, \@row_words;
    }
    
    # Place a glider in the top-left area
    # Pattern:  .#.
    #           ..#
    #           ###
    set_cell(\@grid, 1, 2, 1);
    set_cell(\@grid, 2, 3, 1);
    set_cell(\@grid, 3, 1, 1);
    set_cell(\@grid, 3, 2, 1);
    set_cell(\@grid, 3, 3, 1);
    
    # Add another glider
    set_cell(\@grid, 10, 10, 1);
    set_cell(\@grid, 11, 12, 1);
    set_cell(\@grid, 12, 10, 1);
    set_cell(\@grid, 12, 11, 1);
    set_cell(\@grid, 12, 12, 1);
    
    return @grid;
}

# Set a single cell (for pattern creation)
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

# Get a single cell value (for display/testing)
sub get_cell {
    my ($grid, $row, $col) = @_;
    return 0 if $row < 0 || $row >= $height || $col < 0 || $col >= $width;
    
    my $word_idx = int($col / 32);
    my $bit_idx = $col % 32;
    
    return ($grid->[$row][$word_idx] >> $bit_idx) & 1;
}

# Count neighbors for a single bit position
sub count_neighbors_bit {
    my ($grid, $row, $word_idx, $bit_idx) = @_;
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

# Compute next generation using bit operations
sub next_generation {
    my @current = @_;
    my @next = ();
    
    # Initialize next generation grid
    for my $row (0 .. $height - 1) {
        my @row_words = (0) x $words_per_row;
        push @next, \@row_words;
    }
    
    # Process each cell
    for my $row (0 .. $height - 1) {
        for my $word_idx (0 .. $words_per_row - 1) {
            my $new_word = 0;
            
            # Process each bit in the word
            for my $bit_idx (0 .. 31) {
                my $col = $word_idx * 32 + $bit_idx;
                last if $col >= $width;
                
                my $neighbors = count_neighbors_bit(\@current, $row, $word_idx, $bit_idx);
                my $current_cell = ($current[$row][$word_idx] >> $bit_idx) & 1;
                
                # Apply Conway's rules
                my $new_cell = 0;
                if ($current_cell) {
                    # Live cell: survives with 2 or 3 neighbors
                    $new_cell = 1 if $neighbors == 2 || $neighbors == 3;
                } else {
                    # Dead cell: becomes alive with exactly 3 neighbors
                    $new_cell = 1 if $neighbors == 3;
                }
                
                $new_word |= ($new_cell << $bit_idx);
            }
            
            $next[$row][$word_idx] = $new_word;
        }
    }
    
    return @next;
}

# Print the grid (for smaller grids)
sub print_grid {
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

# Count total live cells efficiently
sub count_live_cells {
    my @grid = @_;
    my $total = 0;
    
    for my $row (0 .. $height - 1) {
        for my $word_idx (0 .. $words_per_row - 1) {
            my $word = $grid[$row][$word_idx];
            # Count bits in word using Brian Kernighan's algorithm
            while ($word) {
                $total++;
                $word &= $word - 1;  # Clear lowest set bit
            }
        }
    }
    return $total;
}

# Main simulation
my @grid;

# Choose initial pattern
if (!$random) {
    print "Starting with glider pattern...\n";
    @grid = generate_glider();
} else {
    print "Starting with random pattern...\n";
    @grid = generate_random();
}

my $start_time = time();
my $print_grid_flag = ($width <= 80 && $height <= 40);

if ($print_grid_flag) {
    print_grid(@grid);
    sleep(1);
}

# Run simulation
for my $gen (1 .. $generations) {
    @grid = next_generation(@grid);
    
    if ($print_grid_flag) {
        print_grid(@grid);
        print "Generation: $gen\n";
        select(undef, undef, undef, 0.1);  # Short delay
    } elsif ($gen % 10 == 0) {
        my $live_cells = count_live_cells(@grid);
        print "Generation $gen: $live_cells live cells\n";
    }
}

my $end_time = time();
my $duration = $end_time - $start_time;
my $final_count = count_live_cells(@grid);

print "\nSimulation completed!\n";
print "Generations: $generations\n";
print "Final live cells: $final_count\n";
print "Time elapsed: ${duration} seconds\n";
print "Generations per second: " . sprintf("%.2f", $generations / $duration) . "\n";

# Performance comparison note
print "\nOptimization notes:\n";
print "- Using bit-packed representation (32 cells per integer)\n";
print "- Memory usage: ~" . sprintf("%.1f", ($height * $words_per_row * 4 / 1024)) . " KB\n";
print "- vs original: ~" . sprintf("%.1f", ($height * $width * 8 / 1024)) . " KB (estimated)\n";

