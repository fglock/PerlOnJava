#!/usr/bin/env perl
use strict;
use warnings;
use Term::ReadLine;
use Term::ReadKey;

print "=== Term::ReadLine and Term::ReadKey Demo ===\n\n";

# Platform detection
my $is_windows = $^O =~ /win/i;
my $platform = $is_windows ? 'Windows' : ($^O eq 'darwin' ? 'macOS' : 'Linux');
my $eof_key = $is_windows ? 'Ctrl-Z' : 'Ctrl-D';
print "Running on: $platform\n";
print "EOF key: $eof_key\n\n";

# Part 1: Term::ReadLine Demo
print "--- Term::ReadLine Demo ---\n";

my $term = Term::ReadLine->new('Term Demo');
my $readline_impl = $term->ReadLine;
print "ReadLine implementation: $readline_impl\n";

# Check features
if (my $features = $term->Features) {
    print "Supported features:\n";
    for my $feature (sort keys %$features) {
        print "  $feature: ", $features->{$feature} ? "yes" : "no", "\n";
    }
}

# Check attributes
if (my $attribs = $term->Attribs) {
    print "\nAttributes:\n";
    for my $attr (sort keys %$attribs) {
        print "  $attr: $attribs->{$attr}\n" if defined $attribs->{$attr};
    }
}

# Console detection
my ($in_name, $out_name) = $term->findConsole();
print "\nConsole files: IN=$in_name, OUT=$out_name\n";

# Set minimum line length for history
$term->MinLine(3);
print "\nMinimum line length for history: ", $term->MinLine(), "\n";

# Interactive readline
print "\n--- Interactive ReadLine Test ---\n";
print "Enter some lines (type 'quit' or press $eof_key to finish):\n";
print "Lines with 3+ characters will be added to history automatically\n\n";

my $prompt = "demo> ";
while (1) {
    my $line = $term->readline($prompt);
    
    # Handle EOF
    if (!defined $line) {
        print "\nEOF detected - exiting readline loop\n";
        last;
    }
    
    # Handle quit command
    if ($line eq 'quit') {
        print "Quit command received\n";
        last;
    }
    
    # Skip empty lines
    if (length($line) == 0) {
        print "Empty line - skipping\n";
        next;
    }
    
    # Display what was entered
    print "You entered: '$line' (length: ", length($line), ")";
    
    # Show if it's printable or has control chars
    if ($line =~ /[\x00-\x1F\x7F]/) {
        print " [contains control characters]";
    }
    print "\n";
    
    # Manually add short lines to history for demo
    if (length($line) < 3) {
        $term->addhistory($line);
        print "  (manually added to history)\n";
    }
}

print "\n--- Term::ReadKey Demo ---\n";

# Get terminal size
print "\nTerminal size:\n";
my @size = GetTerminalSize();
if (@size >= 2) {
    print "  Width: $size[0], Height: $size[1]\n";
    print "  Pixels: $size[2] x $size[3]\n" if defined $size[2] && $size[2];
} else {
    print "  Could not determine terminal size\n";
}

# Get terminal speed (Unix only)
unless ($is_windows) {
    my @speed = GetSpeed();
    if (@speed) {
        print "\nTerminal speed: IN=$speed[0], OUT=$speed[1] baud\n";
    }
}

# Get control characters (Unix only)
unless ($is_windows) {
    print "\nControl characters:\n";
    my %ctrl = GetControlChars();
    for my $key (sort keys %ctrl) {
        my $val = $ctrl{$key};
        next unless defined $val && length($val) > 0;
        my $ord = ord($val);
        my $display = $ord < 32 ? sprintf("^%c (0x%02X)", $ord + 64, $ord) 
                    : $ord == 127 ? "DEL (0x7F)"
                    : "'$val' (0x%02X)";
        print "  $key: $display\n";
    }
}

# Terminal mode tests
print "\n--- Terminal Mode Tests ---\n";
print "Note: On Unix/Mac, Ctrl-Z will suspend the program. Use 'fg' to resume.\n" unless $is_windows;

# Test 1: Normal mode
print "\nTest 1 - Normal mode (echo on, line buffered):\n";
print "Type a line and press Enter: ";
$| = 1;  # Flush output
ReadMode('normal');
my $input = ReadLine(0);
if (defined $input) {
    chomp($input);
    print "You typed: '$input'\n";
} else {
    print "No input received\n";
}

# Test 2: NoEcho mode
print "\nTest 2 - NoEcho mode (echo off, line buffered):\n";
print "Type a password and press Enter: ";
$| = 1;  # Flush output
ReadMode('noecho');
my $password = ReadLine(0);
ReadMode('normal');
if (defined $password) {
    chomp($password);
    print "\nYou typed: ", "*" x length($password), " (", length($password), " chars)\n";
} else {
    print "\nNo password entered\n";
}

# Test 3: Cbreak mode (Unix only)
unless ($is_windows) {
    print "\nTest 3 - Cbreak mode (immediate char, no echo):\n";
    print "Press any key (or 's' to skip): ";
    $| = 1;  # Flush output
    
    ReadMode('cbreak');
    my $key = ReadKey(0);
    ReadMode('normal');
    
    if (defined $key && $key ne 's') {
        my $ord = ord($key);
        print "\nYou pressed: ";
        if ($ord < 32) {
            print "Ctrl-", chr($ord + 64), " (ASCII $ord)";
            print " [EOF]" if $ord == 4;  # Ctrl-D
            print " [SUSPEND]" if $ord == 26;  # Ctrl-Z
        } elsif ($ord == 127) {
            print "Delete (ASCII 127)";
        } else {
            print "'$key' (ASCII $ord)";
        }
        print "\n";
    } elsif (!defined $key) {
        print "\nNo key detected\n";
    } else {
        print "\nSkipped\n";
    }
}

# Test 4: Non-blocking read
print "\nTest 4 - Non-blocking read test:\n";
print "Type some characters (I'll collect them for 3 seconds):\n";
$| = 1;  # Flush output

ReadMode('cbreak') unless $is_windows;
my $start_time = time;
my $chars = '';
my $count = 0;

while (time - $start_time < 3) {
    my $char = ReadKey(-1);  # Non-blocking
    if (defined $char) {
        $chars .= $char;
        $count++;
        # Show the character (or its code if control char)
        if (ord($char) < 32) {
            print "[^", chr(ord($char) + 64), "]";
        } else {
            print $char;
        }
        $| = 1;  # Flush each character
    }
    select(undef, undef, undef, 0.05);  # 50ms delay
}

ReadMode('normal');
print "\nCollected $count characters in 3 seconds\n";
if ($count > 0) {
    print "Raw data: ";
    for my $c (split //, $chars) {
        my $ord = ord($c);
        if ($ord < 32 || $ord == 127) {
            printf "[0x%02X]", $ord;
        } else {
            print $c;
        }
    }
    print "\n";
}

# Test 5: Timed read
print "\nTest 5 - Timed read (2 second timeout):\n";
print "Press a key within 2 seconds: ";
$| = 1;  # Flush output

my $timed_key = ReadKey(2);
if (defined $timed_key) {
    my $ord = ord($timed_key);
    if ($ord < 32) {
        printf "You pressed Ctrl-%c (0x%02X)\n", $ord + 64, $ord;
    } else {
        print "You pressed '$timed_key' (ASCII $ord)\n";
    }
} else {
    print "Timeout - no key pressed\n";
}

# Cleanup
ReadMode('restore');
print "\n=== Demo Complete ===\n";

# Final note about suspended process
unless ($is_windows) {
    print "\nNote: If the program appears frozen, you may have pressed Ctrl-Z.\n";
    print "Type 'fg' and press Enter to resume the program.\n";
}

