use strict;
use feature 'say';

###################
# Perl s///e vs s///ee Operator Tests

# Initial string with an arithmetic expression
my $string = "2+3";
my $pattern = qr/(\d+)\+(\d+)/;

# Using /e: Transform the expression into a string that represents another expression
my $result = $string =~ s/$pattern/"$1 * $2"/e; # This results in the string '2 * 3'
print "not " if !$result; say "ok # '2+3' transforms to '2 * 3' with /e modifier";
print "not " if $string ne '2 * 3'; say "ok # Result is '2 * 3' <$string>";

# Reset the string for /ee test
$string = "2+3";

# Using /ee: Evaluate the expression twice
# First evaluation: '2 + 3' -> '5'
# Second evaluation: '5' is treated as a string and evaluated again
$result = $string =~ s/$pattern/'$1 * $2'/ee;
print "not " if !$result; say "ok # '2+3' evaluates to '6' with /ee modifier";
print "not " if $string ne '6'; say "ok # Result is '6' <$string>";

###################
# End of Tests

