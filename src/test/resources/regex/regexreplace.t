use feature 'say';
use strict;

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


