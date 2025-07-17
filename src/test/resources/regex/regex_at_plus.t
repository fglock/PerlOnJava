use strict;
use feature 'say';

print "1..10\n";

###################
# Perl @- and @+ Operator Tests

# Simple match - test @- and @+
my $string = "Hello World";
my $pattern = qr/(World)/;
my $match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello World' matches 'World'";

# Test @- and @+ arrays for start and end positions
if ($match) {
    print "not " if ($-[0] != 6 || $+[0] != 11);  # Entire match
    say "ok # @- and @+ store the correct start and end positions for the match";

    print "not " if ($-[1] != 6 || $+[1] != 11);  # Capture group (World)
    say "ok # @- and @+ store the correct positions for capture group";
}

# Match with multiple capture groups
$string = "Hello beautiful World";
$pattern = qr/(beautiful) (World)/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello beautiful World' matches 'beautiful World'";

# Test @- and @+ for multiple capture groups
if ($match) {
    print "not " if ($-[0] != 6 || $+[0] != 21);  # Entire match
    say "ok # @- and @+ store correct start and end positions for the whole match";

    print "not " if ($-[1] != 6 || $+[1] != 15);  # First capture group (beautiful)
    say "ok # @- and @+ store correct start and end positions for 'beautiful'";

    print "not " if ($-[2] != 16 || $+[2] != 21);  # Second capture group (World)
    say "ok # @- and @+ store correct start and end positions for 'World'";
}

# Match with no captures
$string = "Just a simple string";
$pattern = qr/simple/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Just a simple string' matches 'simple'";

# Test @- and @+ with no capture groups
if ($match) {
    print "not " if ($-[0] != 7 || $+[0] != 13);  # Entire match
    say "ok # @- and @+ work without capture groups";
}

# Test with no match
$string = "No match here";
$pattern = qr/not_here/;
$match = $string =~ $pattern;
print "not " if $match; say "ok # 'No match here' does not match 'not_here'";

## # Ensure @- and @+ are not populated when there's no match
## if (!$match) {
##     print "not " if !(defined $-[0] || defined $+[0]);
##     say "ok # @- and @+ are not undef when no match occurs";
## }


