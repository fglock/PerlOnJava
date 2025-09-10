use feature 'say';
use strict;

print "1..12\n";

###################
# Perl s/// Operator Tests

# Simple substitution
my $string = "Hello World";
my $pattern = qr/World/;
my $replacement = "Universe";
my $substituted = $string =~ s/$pattern/$replacement/r;
print "not " if $substituted ne "Hello Universe"; say "ok # 'Hello World' becomes 'Hello Universe'";

# No substitution
$string = "Hello World";
$pattern = qr/Universe/;
$replacement = "Galaxy";
$substituted = $string =~ s/$pattern/$replacement/r;
print "not " if $substituted ne "Hello World"; say "ok # 'Hello World' remains 'Hello World'";

# Global substitution
$string = "Hello World World";
$pattern = qr/World/;
$replacement = "Universe";
$substituted = $string =~ s/$pattern/$replacement/gr;
print "not " if $substituted ne "Hello Universe Universe"; say "ok # 'Hello World World' becomes 'Hello Universe Universe'";

# Substitution with captured groups
$string = "Hello World";
$pattern = qr/(World)/;
$replacement = "Universe";
$substituted = $string =~ s/$pattern/$replacement/r;
print "not " if $substituted ne "Hello Universe"; say "ok # 'Hello World' becomes 'Hello Universe' with captured group";

# Substitution with code
$string = "Hello World";
$pattern = qr/(World)/;
$replacement = sub { return "Universe"; };
$substituted = $string =~ s/$pattern/$replacement->()/er;
print "not " if $substituted ne "Hello Universe"; say "ok # 'Hello World' becomes 'Hello Universe' with code replacement";

# Substitution with multiple captured groups
$string = "Hello World";
$pattern = qr/(Hello) (World)/;
$substituted = $string =~ s/$pattern/$2, $1/r;
print "not " if $substituted ne "World, Hello"; say "ok # 'Hello World' becomes 'World, Hello' with multiple captured groups <$substituted>";

# Substitution with backslash
$string = "Hello World\n";
$pattern = qr/\n/;
$replacement = "\\n";
$substituted = $string =~ s/$pattern/$replacement/r;
print "not " if $substituted ne "Hello World\\n"; say 'ok # \n becomes \\\\n with backslash';

###################
# Tests for /r modifier bug

# Test 8: /r modifier with no match should return original string
$string = "Hello World";
$substituted = $string =~ s/foo/bar/r;
print "not " if $substituted ne "Hello World";
say "ok 8 # s///r with no match returns original: got [$substituted]";

# Test 9: /r modifier with pattern that doesn't match
$string = "/[[=foo=]]/";
$substituted = $string =~ s/ default_ (on | off) //rx;
print "not " if $substituted ne "/[[=foo=]]/";
say "ok 9 # s///r with non-matching pattern returns original: got [$substituted]";

# Test 10: /r modifier preserves original string
$string = "test123";
$substituted = $string =~ s/123/456/r;
print "not " if $string ne "test123";
say "ok 10 # s///r preserves original string: [$string]";
print "not " if $substituted ne "test456";
say "ok 11 # s///r returns modified string: [$substituted]";

# Test 12: Empty pattern with /r
$string = "test";
$substituted = $string =~ s/nomatch//r;
print "not " if $substituted ne "test";
say "ok 12 # s///r with non-matching empty replacement returns original: got [$substituted]";

