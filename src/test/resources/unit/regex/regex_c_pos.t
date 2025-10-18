use strict;
use feature 'say';

print "1..4\n";

my $str = "hello hello hello";

# Test where /c makes a difference
pos($str) = 6;  # Position at second "hello"

# Without /c - matches from current position
$str =~ /hello/g;  
print "not " if pos($str) != 11; say "ok # without /c, matched from pos=6, pos=11";

# With /c - also matches from current pos
pos($str) = 6;
$str =~ /hello/gc;
print "not " if pos($str) != 11; say "ok # with /c, matched from pos=6 to pos=11";


# Now test with a failed match to see the difference
pos($str) = 6;
$str =~ /xyz/g;  # Fails and resets pos()
print "not " if defined pos($str); say "ok # /g resets pos() on failure";

pos($str) = 6;
$str =~ /xyz/gc;  # Fails but keeps pos()
print "not " if pos($str) != 6; say "ok # /gc preserves pos() on failure";

