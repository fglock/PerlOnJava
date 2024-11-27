use feature 'say';
use strict;

###################
# Perl tr/// Operator Tests

# Simple transliteration
my $string = "Hello World";
my $trans = $string =~ tr/o/O/;
print "not " if $string ne "HellO WOrld"; say "ok # 'Hello World' transliterates 'o' to 'O'";

# Multiple character transliteration
$string = "Hello World";
$trans = $string =~ tr/lo/LO/;
print "not " if $string ne "HeLLO WOrLd"; say "ok # 'Hello World' transliterates 'l' to 'L' and 'o' to 'O'";

# Transliteration with character range
$string = "abcdef";
$trans = $string =~ tr/a-f/A-F/;
print "not " if $string ne "ABCDEF"; say "ok # 'abcdef' transliterates 'a-f' to 'A-F'";

# Transliteration with deletion
$string = "Hello World";
$trans = $string =~ tr/o//d;
print "not " if $string ne "Hell Wrld"; say "ok # 'Hello World' deletes 'o' characters: <$string>";

# Transliteration with complement
$string = "Hello World";
$trans = $string =~ tr/a-zA-Z/-/c;
print "not " if $string ne "Hello-World"; say "ok # 'Hello World' changes all alphabetic characters";

# Transliteration with squeeze
$string = "Hellooo   World";
$trans = $string =~ tr/o/0/s;
print "not " if $string ne "Hell0   W0rld"; say "ok # 'Hellooo   World' squeezes 'o' characters to '0'";

# Transliteration with complement and squeeze
$string = "Hellooo   World";
$trans = $string =~ tr/a-zA-Z/-/cs;
print "not " if $string ne "Hellooo-World"; say "ok # 'Hellooo   World' deletes non-alphabetic characters and squeezes spaces: <$string>";

# Transliteration with range and deletion
$string = "abcdef";
$trans = $string =~ tr/a-f/A-F/d;
print "not " if $string ne "ABCDEF"; say "ok # 'abcdef' transliterates 'a-f' to 'A-F' and deletes characters not in the range";

# Transliteration with range and complement
$string = "abcdef";
$trans = $string =~ tr/-a-f/-A-F/c;
print "not " if $string ne "abcdef"; say "ok # 'abcdef' does not change as complement of 'a-f' is empty: <$string>";

# Transliteration with range, complement, and deletion
$string = "abcdef";
$trans = $string =~ tr/a-f/A-F/cd;
print "not " if $string ne "abcdef"; say "ok # 'abcdef' deletes all characters as complement of 'a-f' is empty: <$string>";

# Transliteration with complement
$string = "Hello World 123!";
$trans = $string =~ tr/a-zA-Z/X/c;
print "not " if $string ne "HelloXWorldXXXXX"; say "ok # 'Hello World 123!' replaces non-alphabetic characters with 'X': <$string>";

# Transliteration with complement and deletion
$string = "Hello World 123!";
$trans = $string =~ tr/a-zA-Z//cd;
print "not " if $string ne "HelloWorld"; say "ok # 'Hello World 123!' deletes non-alphabetic characters: <$string>";

# Transliteration with complement and squeeze
$string = "Hello World 123!";
$trans = $string =~ tr/a-zA-Z/X/cs;
print "not " if $string ne "HelloXWorldX"; say "ok # 'Hello World 123!' replaces non-alphabetic characters with 'X' and squeezes them: <$string>";


