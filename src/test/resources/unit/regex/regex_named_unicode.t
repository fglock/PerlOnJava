use strict;
use Test::More;
use warnings;
use feature 'say';
use utf8;

# Test for \N{name} constructs

# Simple match with a named Unicode character
my $string = "Hello \N{LATIN SMALL LETTER E WITH ACUTE}";
my $pattern = qr/\N{LATIN SMALL LETTER E WITH ACUTE}/;
my $match = $string =~ $pattern;
ok($match, '\'Hello é\' matches \'\\N{LATIN SMALL LETTER E WITH ACUTE}\'');

# Match with multiple named Unicode characters
$string = "\N{LATIN CAPITAL LETTER A} and \N{LATIN SMALL LETTER A}";
$pattern = qr/\N{LATIN CAPITAL LETTER A} and \N{LATIN SMALL LETTER A}/;
$match = $string =~ $pattern;
ok($match, '\'A and a\' matches \'\\N{LATIN CAPITAL LETTER A} and \\N{LATIN SMALL LETTER A}\'');

# No match with incorrect named Unicode character
$string = "Hello \N{LATIN SMALL LETTER E WITH ACUTE}";
$pattern = qr/\N{LATIN SMALL LETTER E}$/;
$match = $string =~ $pattern;
ok(!($match), '\'Hello é\' does not match \'\\N{LATIN SMALL LETTER E}\'');

# Match with named Unicode character in a sentence
$string = "The café is open.";
$pattern = qr/caf\N{LATIN SMALL LETTER E WITH ACUTE}/;
$match = $string =~ $pattern;
ok($match, '\'The café is open.\' matches \'caf\\N{LATIN SMALL LETTER E WITH ACUTE}\'');

# Match with named Unicode character using case insensitive flag
$string = "HELLO \N{LATIN SMALL LETTER E WITH ACUTE}";
$pattern = qr/\N{LATIN SMALL LETTER E WITH ACUTE}/i;
$match = $string =~ $pattern;
ok($match, '\'HELLO é\' matches \'\\N{LATIN SMALL LETTER E WITH ACUTE}\' case insensitively');

# Match with named Unicode character and quantifiers
$string = "\N{LATIN SMALL LETTER E WITH ACUTE}\N{LATIN SMALL LETTER E WITH ACUTE}\N{LATIN SMALL LETTER E WITH ACUTE}";
$pattern = qr/(\N{LATIN SMALL LETTER E WITH ACUTE}){3}/;
$match = $string =~ $pattern;
ok($match, '\'ééé\' matches \'(\\N{LATIN SMALL LETTER E WITH ACUTE}){3}\'');

# Match with named Unicode character and alternation
$string = "apple \N{LATIN SMALL LETTER E WITH ACUTE}";
$pattern = qr/apple|\N{LATIN SMALL LETTER E WITH ACUTE}/;
$match = $string =~ $pattern;
ok($match, '\'apple é\' matches \'apple|\\N{LATIN SMALL LETTER E WITH ACUTE}\'');

# Match with named Unicode character in list context
$string = "Hello \N{LATIN SMALL LETTER E WITH ACUTE} World";
$pattern = qr/(\N{LATIN SMALL LETTER E WITH ACUTE})/;
my @matches = $string =~ $pattern;
ok(!(scalar(@matches) != 1), '\'Hello é World\' matches \'\\N{LATIN SMALL LETTER E WITH ACUTE}\' in list context');
ok(!($matches[0] ne "\N{LATIN SMALL LETTER E WITH ACUTE}"), '\$matches[0] is \'é\'');

done_testing();
