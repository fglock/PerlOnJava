use feature 'say';
use strict;
use Test::More;

###################
# Perl s/// Operator Tests

# Simple substitution
my $string = "Hello World";
my $pattern = qr/World/;
my $replacement = "Universe";
my $substituted = $string =~ s/$pattern/$replacement/r;
ok(!($substituted ne "Hello Universe"), '\'Hello World\' becomes \'Hello Universe\'');

# No substitution
$string = "Hello World";
$pattern = qr/Universe/;
$replacement = "Galaxy";
$substituted = $string =~ s/$pattern/$replacement/r;
ok(!($substituted ne "Hello World"), '\'Hello World\' remains \'Hello World\'');

# Global substitution
$string = "Hello World World";
$pattern = qr/World/;
$replacement = "Universe";
$substituted = $string =~ s/$pattern/$replacement/gr;
ok(!($substituted ne "Hello Universe Universe"), '\'Hello World World\' becomes \'Hello Universe Universe\'');

# Substitution with captured groups
$string = "Hello World";
$pattern = qr/(World)/;
$replacement = "Universe";
$substituted = $string =~ s/$pattern/$replacement/r;
ok(!($substituted ne "Hello Universe"), '\'Hello World\' becomes \'Hello Universe\' with captured group');

# Substitution with code
$string = "Hello World";
$pattern = qr/(World)/;
$replacement = sub { return "Universe"; };
$substituted = $string =~ s/$pattern/$replacement->()/er;
ok(!($substituted ne "Hello Universe"), '\'Hello World\' becomes \'Hello Universe\' with code replacement');

# Substitution with multiple captured groups
$string = "Hello World";
$pattern = qr/(Hello) (World)/;
$substituted = $string =~ s/$pattern/$2, $1/r;
ok(!($substituted ne "World, Hello"), '\'Hello World\' becomes \'World, Hello\' with multiple captured groups <$substituted>');

# Substitution with backslash
$string = "Hello World\n";
$pattern = qr/\n/;
$replacement = "\\n";
$substituted = $string =~ s/$pattern/$replacement/r;
ok($substituted eq "Hello World\\n", '\\n becomes \\\\n with backslash');

###################
# Tests for /r modifier bug

# /r modifier with no match should return original string
$string = "Hello World";
$substituted = $string =~ s/foo/bar/r;
ok($substituted eq "Hello World", "s///r with no match returns original: got [$substituted]");

# /r modifier with pattern that doesn't match
$string = "/[[=foo=]]/";
$substituted = $string =~ s/ default_ (on | off) //rx;
ok($substituted eq "/[[=foo=]]/", "s///r with non-matching pattern returns original: got [$substituted]");

# /r modifier preserves original string
$string = "test123";
$substituted = $string =~ s/123/456/r;
ok($string eq "test123", "s///r preserves original string: [$string]");
ok($substituted eq "test456", "s///r returns modified string: [$substituted]");

# Empty pattern with /r
$string = "test";
$substituted = $string =~ s/nomatch//r;
ok($substituted eq "test", "s///r with non-matching empty replacement returns original: got [$substituted]");

done_testing();
