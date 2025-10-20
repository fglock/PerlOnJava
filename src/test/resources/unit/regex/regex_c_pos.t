use strict;
use Test::More;
use feature 'say';

my $str = "hello hello hello";

# Test where /c makes a difference
pos($str) = 6;  # Position at second "hello"

# Without /c - matches from current position
$str =~ /hello/g;  
ok(!(pos($str) != 11), 'without /c, matched from pos=6, pos=11');

# With /c - also matches from current pos
pos($str) = 6;
$str =~ /hello/gc;
ok(!(pos($str) != 11), 'with /c, matched from pos=6 to pos=11');

# Now test with a failed match to see the difference
pos($str) = 6;
$str =~ /xyz/g;  # Fails and resets pos()
ok(!(defined pos($str)), '/g resets pos() on failure');

pos($str) = 6;
$str =~ /xyz/gc;  # Fails but keeps pos()
ok(!(pos($str) != 6), '/gc preserves pos() on failure');

done_testing();
