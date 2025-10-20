use strict;
use Test::More;
use warnings;
use feature 'say';

###################
# Perl m?PAT? Operator Tests

# Simple match that should only match once without reset
my $string  = "apple orange apple";
my $match;

for my $state ( 1, 0 ) {
    $match = $string =~ m?apple?;
    ok($match == $state, "match: 'apple' " . ($match ? "found" : "not found"));
}

# # Use reset() to allow matching again
# reset();
# $match = $string =~ m?$pattern?;
# print "not " if !$match;
# say "ok # After reset: 'apple' found again";

done_testing();
