use strict;
use warnings;
use 5.38.0;
use utf8;
use open ':std', ':encoding(UTF-8)';

###################
# Perl Unicode Property Tests

# Test for Letter property (\p{L})
my $string = "Hello世界";
my $pattern = qr/\p{L}+/;
my $match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello世界' matches '\\p{L}+'";

# Test for non-Letter property (\P{L})
$string = "123!@#";
$pattern = qr/\P{L}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # '123!@#' matches '\\P{L}+'";

# Test for Number property (\p{N})
$string = "42３６९";  # Mix of ASCII and Unicode numbers
$pattern = qr/\p{N}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # '42３６９' matches '\\p{N}+'";

# Test for non-Number property (\P{N})
$string = "abc!@#";
$pattern = qr/\P{N}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'abc!@#' matches '\\P{N}+'";

# Test for Punctuation property (\p{P})
$string = "Hello, World！";  # Including Unicode punctuation
$pattern = qr/\p{P}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello, World！' matches '\\p{P}+'";

# Test for Symbol property (\p{S})
$string = "\$€¥£";
$pattern = qr/\p{S}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # '\$€¥£' matches '\\p{S}+'";

# Test for Mark property (\p{M})
$string = "e\x{0301}";  # e + combining acute accent (U+0301)
$pattern = qr/\p{M}/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'é' matches '\\p{M}'";

# Test for multiple properties
$string = "Hello123！";
$pattern = qr/[\p{L}\p{N}\p{P}]+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'Hello123！' matches '[\\p{L}\\p{N}\\p{P}]+'";

# Test for script property
$string = "こんにちは";
$pattern = qr/\p{Script=Hiragana}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # 'こんにちは' matches '\\p{Script=Hiragana}+'";

# Test for block property
$string = "世界";
$pattern = qr/\p{Block=CJK_Unified_Ideographs}+/;
$match = $string =~ $pattern;
print "not " if !$match; say "ok # '世界' matches '\\p{Block=CJK_Unified_Ideographs}+'";

