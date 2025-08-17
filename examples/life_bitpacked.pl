#!/usr/bin/perl
use strict;
use warnings;
use Getopt::Long;

# Bit-packed Conway's Game of Life
# Author: Claude (Anthropic)
# Uses 32-bit integers to store 32 cells per integer for memory efficiency
# and faster operations through bitwise manipulation

use utf8;
binmode(STDOUT, ":utf8");

# Default values
my $width = 64;
my $height = 32;
my $generations = 100;
my $resolution = "auto";  # auto, block, braille, none
my $pattern = "auto";     # auto, glider, random
my $help = 0;

# Parse command line options
GetOptions(
    'width|w=i'        => \$width,
    'height|h=i'       => \$height,
    'generations|g=i'  => \$generations,
    'resolution|r=s'   => \$resolution,
    'pattern|p=s'      => \$pattern,
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
    -w, --width N          Grid width (default: 64, rounded to multiple of 32)
    -h, --height N         Grid height (default: 32)
    -g, --generations N    Number of generations to simulate (default: 100)
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
    --help                 Show this help message

EXAMPLES:
    perl life.pl                                    # Default 64x32 grid
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

# Print the grid using simple ASCII characters
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

# Print the grid using high-resolution block characters
sub print_grid {
    my @grid = @_;
    my $total_cells = 0;
    
    print "\e[H\e[2J";  # Clear screen and move cursor to top
    
    # Use 2x2 block characters for 4x resolution
    # Each character represents a 2x2 cell area
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

# Alternative: Print using Braille characters for even higher resolution (2x4)
sub print_grid_braille {
    my @grid = @_;
    my $total_cells = 0;
    
    print "\e[H\e[2J";  # Clear screen and move cursor to top
    
    # Braille Unicode block: U+2800 to U+28FF
    # Each character can represent 2x4 cells (8 dots)
    # Dot positions:
    # 1 4
    # 2 5  
    # 3 6
    # 7 8
    
    for (my $row = 0; $row < $height; $row += 4) {
        for (my $col = 0; $col < $width; $col += 2) {
            my $pattern = 0;
            my $cell_count = 0;
            
            # Map cells to braille dot positions
            my @positions = (
                [$row,     $col],     # dot 1
                [$row + 1, $col],     # dot 2
                [$row + 2, $col],     # dot 3
                [$row,     $col + 1], # dot 4
                [$row + 1, $col + 1], # dot 5
                [$row + 2, $col + 1], # dot 6
                [$row + 3, $col],     # dot 7
                [$row + 3, $col + 1]  # dot 8
            );
            
            for my $i (0 .. 7) {
                my ($r, $c) = @{$positions[$i]};
                my $cell = get_cell(\@grid, $r, $c);
                if ($cell) {
                    $pattern |= (1 << $i);
                    $cell_count++;
                }
            }
            
            # Convert to Unicode Braille
            my $braille_char = chr(0x2800 + $pattern);
            print $braille_char;
            
            $total_cells += $cell_count;
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

# Choose initial pattern based on settings
if ($pattern_type eq "glider") {
    print "Creating glider pattern...\n";
    @grid = generate_glider();
} else {
    print "Generating random pattern...\n";
    @grid = generate_random();
}

my $start_time = time();

if ($display_mode ne "none") {
    if ($display_mode eq "braille") {
        print_grid_braille(@grid);
    } elsif ($display_mode eq "ascii") {
        print_grid_ascii(@grid);
    } else {
        print_grid(@grid);
    }
    sleep(1);
}

# Run simulation
for my $gen (1 .. $generations) {
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

