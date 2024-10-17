use strict;
use feature 'say';

###################
# Perl `pos` Function and `\G` Assertion Tests

# Test `pos` function to get the position of the last match
my $string = "abc def ghi";
my $pattern = qr/(\w+)/;  # Use a capture group to capture the match

# Initial position should be undefined
print "not " if defined pos($string); say "ok # Initial pos is undefined before matching";

# Perform a global match and check positions
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group instead of {text}
    my $expected_pos = pos($string);
    print "not " if $match ne substr($string, $expected_pos - length($match), length($match));
    say "ok # Match '$match' ends at position $expected_pos";
}

# Reset position and check
pos($string) = 0;
print "not " if pos($string) != 0; say "ok # pos reset to 0";

# Test `\G` to match at the position where the last `m//g` left off
$string = "123 456 789";
$pattern = qr/(\d{3})/;  # Use a capture group

# Match using \G
while ($string =~ /\G$pattern/g) {
    my $match = $1;  # Use the captured group
    print "not " if $match !~ /^\d{3}$/; say "ok # Match '$match' using \\G";
}

# Test `\G` with intervening non-matching characters
$string = "123 abc 456 def 789";
$pattern = qr/\G(\d{3})/;  # Use a capture group

# Match using \G
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group
    print "not " if $match !~ /^\d{3}$/; say "ok # Match '$match' using \\G with intervening text";
}

# Test `pos` with manual setting
$string = "abc def ghi";
$pattern = qr/(\w+)/;  # Use a capture group

# manual pos set
pos($string) = 4;
$string =~ /$pattern/g;
my $match = $1;
my $current_pos = pos($string);
print "not " if $match ne 'def'; say "ok # Manual pos set to 4, matched 'def'";

# Test `\G` with alternation
$string = "123abc456";
$pattern = qr/\G((?:\d{3}|abc))/;  # Use a capture group

# Match using \G with alternation
while ($string =~ /$pattern/g) {
    my $match = $1;  # Use the captured group
    print "not " if $match !~ /^(?:\d{3}|abc)$/; say "ok # Match '$match' using \\G with alternation";
}

# Test `pos` after failed match
$string = "abc def ghi";
$pattern = qr/(\d+)/;  # Use a capture group

# Attempt to match digits
$string =~ /$pattern/g;
print "not " if defined pos($string); say "ok # pos is undefined after failed match";

# non-global match
$string = "123 456";
$pattern = qr/\G(\d{3})/;  # Use a capture group

# Non-global match should not use \G
$string =~ /$pattern/;
print "not " if $1 ne '123'; say "ok # Non-global match does not use \\G, matched '123'";
###################
# End of Perl `pos` and `\G` Tests

