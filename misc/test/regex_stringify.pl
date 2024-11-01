use strict;
use feature 'say';

# Test with no flags
my $pattern = qr/World/;
my $ref = "" . $pattern;
print "not " if $ref ne "(?^:World)"; say "ok # ref <$ref> with no flags";

# Test with case insensitive flag
$pattern = qr/World/i;
$ref = "" . $pattern;
print "not " if $ref ne "(?^i:World)"; say "ok # ref <$ref> with case insensitive flag";

# Test with multiline flag
$pattern = qr/World/m;
$ref = "" . $pattern;
print "not " if $ref ne "(?^m:World)"; say "ok # ref <$ref> with multiline flag";

# Test with dotall flag
$pattern = qr/World/s;
$ref = "" . $pattern;
print "not " if $ref ne "(?^s:World)"; say "ok # ref <$ref> with dotall flag";

# Test with comments flag
$pattern = qr/World/x;
$ref = "" . $pattern;
print "not " if $ref ne "(?^x:World)"; say "ok # ref <$ref> with comments flag";

# Test with multiple flags
$pattern = qr/World/imsx;
$ref = "" . $pattern;
print "not " if $ref ne "(?^msix:World)"; say "ok # ref <$ref> with multiple flags";

# Test with a complex pattern
$pattern = qr/(Hello) (World)/;
$ref = "" . $pattern;
print "not " if $ref ne "(?^:(Hello) (World))"; say "ok # ref <$ref> with complex pattern";

