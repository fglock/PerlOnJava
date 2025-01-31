use strict;
use warnings;
use utf8;
use feature 'say';
use open ':std', ':encoding(UTF-8)';

###################
# Perl Extended Unicode Regex Features Tests

# Test 1: \X matches a single grapheme cluster
my $string = "e\x{0301}";  # e + combining acute accent
my $pattern = qr/^\X$/;    # Matches as single grapheme cluster
my $match = $string =~ $pattern;
print "not " if !$match; say "ok 1 # 'e\\x{0301}' matches '^\\X\$' as single grapheme cluster";

# Test 2: \X matches a single emoji with skin tone modifier
$string = "\x{1F44B}\x{1F3FB}";  # Waving hand emoji + light skin tone
$match = $string =~ $pattern;
print "not " if !$match; say "ok 2 # '\\x{1F44B}\\x{1F3FB}' matches '^\\X\$' as single grapheme cluster";

# Test 3: \X matches a sequence with zero-width joiner (ZWJ)
$string = "\x{1F469}\x{200D}\x{1F91D}\x{200D}\x{1F468}";  # Woman and man holding hands (family emoji)
$match = $string =~ $pattern;
print "not " if !$match; say "ok 3 # '\\x{1F469}\\x{200D}\\x{1F91D}\\x{200D}\\x{1F468}' matches '^\\X\$' as single grapheme cluster";

# Test 4: \X does not match multiple grapheme clusters
$string = "e\x{0301}e";  # Two grapheme clusters
$match = $string =~ $pattern;
print "not " if $match; say "ok 4 # 'e\\x{0301}e' does not match '^\\X\$' as single grapheme cluster";

# Test 5: \X matches a single standalone character
$string = "A";  # Basic Latin character
$match = $string =~ $pattern;
print "not " if !$match; say "ok 5 # 'A' matches '^\\X\$' as single grapheme cluster";

# Test 6: \X matches regional indicator symbols
$string = "\x{1F1FA}\x{1F1F8}";  # US flag
$match = $string =~ $pattern;
print "not " if !$match; say "ok 6 # Regional indicator symbols match as single grapheme cluster";

# Test 7: \X matches emoji + variation selector
$string = "\x{2764}\x{FE0F}";  # Red heart with variation selector
$match = $string =~ $pattern;
print "not " if !$match; say "ok 7 # Emoji with variation selector matches as single grapheme cluster";

# Test 8: \X matches family emoji sequence
$string = "\x{1F468}\x{200D}\x{1F469}\x{200D}\x{1F467}"; # Family MWG
$match = $string =~ $pattern;
print "not " if !$match; say "ok 8 # Family emoji sequence matches as single grapheme cluster";

# Test 9: \X matches profession emoji with ZWJ
$string = "\x{1F469}\x{200D}\x{1F3EB}"; # Woman teacher
$match = $string =~ $pattern;
print "not " if !$match; say "ok 9 # Professional emoji with ZWJ matches as single grapheme cluster";
