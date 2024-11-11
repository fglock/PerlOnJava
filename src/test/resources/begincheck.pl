#!/usr/bin/perl
use strict;
use warnings;

# Initialize a counter for test tracking
my $test_counter;
BEGIN { $test_counter = 1; }

# Test ordinary code execution at runtime
print "not " if $test_counter != 10;
print "ok ", $test_counter++, " - Ordinary code runs at runtime.\n";

# Test END block execution
END { print "not " if $test_counter != 16; print "ok ", $test_counter++, " - So this is the end of the tale.\n"; }

# Test INIT block execution
INIT { print "not " if $test_counter != 7; print "ok ", $test_counter++, " - INIT blocks run FIFO just before runtime.\n"; }

# Test UNITCHECK block execution
UNITCHECK { print "not " if $test_counter != 4; print "ok ", $test_counter++, " - And therefore before any CHECK blocks.\n"; }

# Test CHECK block execution
CHECK { print "not " if $test_counter != 6; print "ok ", $test_counter++, " - So this is the sixth line.\n"; }

# Test ordinary code execution order
print "not " if $test_counter != 11;
print "ok ", $test_counter++, " - It runs in order, of course.\n";

# Test BEGIN block execution
BEGIN { print "not " if $test_counter != 1; print "ok ", $test_counter++, " - BEGIN blocks run FIFO during compilation.\n"; }

# Test another END block execution
END { print "not " if $test_counter != 15; print "ok ", $test_counter++, " - Read perlmod for the rest of the story.\n"; }

# Test another CHECK block execution
CHECK { print "not " if $test_counter != 5; print "ok ", $test_counter++, " - CHECK blocks run LIFO after all compilation.\n"; }

# Test another INIT block execution
INIT { print "not " if $test_counter != 8; print "ok ", $test_counter++, " - Run this again, using Perl's -c switch.\n"; }

# Test another ordinary code execution
print "not " if $test_counter != 12;
print "ok ", $test_counter++, " - This is anti-obfuscated code.\n";

# Test another END block execution
END { print "not " if $test_counter != 14; print "ok ", $test_counter++, " - END blocks run LIFO at quitting time.\n"; }

# Test another BEGIN block execution
BEGIN { print "not " if $test_counter != 2; print "ok ", $test_counter++, " - So this line comes out second.\n"; }

# Test another UNITCHECK block execution
UNITCHECK { print "not " if $test_counter != 3; print "ok ", $test_counter++, " - UNITCHECK blocks run LIFO after each file is compiled.\n"; }

# Test another INIT block execution
INIT { print "not " if $test_counter != 9; print "ok ", $test_counter++, " - You'll see the difference right away.\n"; }

# Test another ordinary code execution
print "not " if $test_counter != 13;
print "ok ", $test_counter++, " - It only _looks_ like it should be confusing.\n";

