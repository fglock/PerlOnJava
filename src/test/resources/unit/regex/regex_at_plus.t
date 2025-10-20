use strict;
use Test::More;
use feature 'say';

###################
# Perl @- and @+ Operator Tests

# Simple match - test @- and @+
my $string = "Hello World";
my $pattern = qr/(World)/;
my $match = $string =~ $pattern;
ok($match, '\'Hello World\' matches \'World\'');

# Test @- and @+ arrays for start and end positions
if ($match) {
    ok($-[0] == 6 && $+[0] == 11, '@- and @+ store the correct start and end positions for the match');
    ok($-[1] == 6 && $+[1] == 11, '@- and @+ store the correct positions for capture group');
}

# Match with multiple capture groups
$string = "Hello beautiful World";
$pattern = qr/(beautiful) (World)/;
$match = $string =~ $pattern;
ok($match, '\'Hello beautiful World\' matches \'beautiful World\'');

# Test @- and @+ for multiple capture groups
if ($match) {
    ok($-[0] == 6 && $+[0] == 21, '@- and @+ store correct start and end positions for the whole match');
    ok($-[1] == 6 && $+[1] == 15, "@- and @+ store correct start and end positions for 'beautiful'");
    ok($-[2] == 16 && $+[2] == 21, "@- and @+ store correct start and end positions for 'World'");
}

# Match with no captures
$string = "Just a simple string";
$pattern = qr/simple/;
$match = $string =~ $pattern;
ok($match, '\'Just a simple string\' matches \'simple\'');

# Test @- and @+ with no capture groups
if ($match) {
    ok($-[0] == 7 && $+[0] == 13, '@- and @+ work without capture groups');
}

# Test with no match
$string = "No match here";
$pattern = qr/not_here/;
$match = $string =~ $pattern;
ok(!($match), '\'No match here\' does not match \'not_here\'');

## # Ensure @- and @+ are not populated when there's no match
## if (!$match) {
##     print "not " if !(defined $-[0] || defined $+[0]);
##     say "ok # @- and @+ are not undef when no match occurs";
## }

done_testing();
