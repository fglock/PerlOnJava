use strict;
use Test::More;
use feature 'say';

###################
# Perl s///e vs s///ee Operator Tests

# Initial string with an arithmetic expression
my $string = "2+3";
my $pattern = qr/(\d+)\+(\d+)/;

# Using /e: Transform the expression into a string that represents another expression
my $result = $string =~ s/$pattern/"$1 * $2"/e; # This results in the string '2 * 3'
ok($result, '\'2+3\' transforms to \'2 * 3\' with /e modifier');
ok(!($string ne '2 * 3'), 'Result is \'2 * 3\' <$string>');

# Reset the string for /ee test
$string = "2+3";

# Using /ee: Evaluate the expression twice
# First evaluation: '2 + 3' -> '5'
# Second evaluation: '5' is treated as a string and evaluated again
$result = $string =~ s/$pattern/'$1 * $2'/ee;
ok($result, '\'2+3\' evaluates to \'6\' with /ee modifier');
ok(!($string ne '6'), 'Result is \'6\' <$string>');

###################
# End of Tests

done_testing();
