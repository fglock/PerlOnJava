use strict;
use feature 'say';

my $str = "aaa bbb aaa";

# Test 1: Normal capturing without /n
$str =~ /(aaa)/;
print "not " if $1 ne "aaa"; 
say "ok # normal capturing works";

# Test 2: Disable capturing with (?n) inline
$str =~ /(?n)(aaa)/;
print "not " if defined $1;
say "ok # (?n) disables capturing";

# Test 3: Disable capturing with (?n:...) group syntax
$str =~ /(?n:(aaa))/;
print "not " if defined $1;
say "ok # (?n:pattern) disables capturing";

# Test 4: Mixed capturing - outside enabled, inside disabled
$str =~ /((?n:aaa) bbb)/;
print "not " unless ($1 eq "aaa bbb");
print "not " if defined $2;
say "ok # mixed capturing works";

# Test 5: Turn off /n with (?^)
$str =~ /(?n)(aaa) (?^)(bbb)/;
print "not " if defined $2;
print "not " if $1 ne "bbb";
say "ok # (?^) restores capturing ($1)($2)";

# Test 6: Multiple groups with /n
$str =~ /(?n)(aaa) (bbb) (aaa)/;
print "not " if defined $1 || defined $2 || defined $3;
say "ok # multiple groups with (?n) don't capture";

# Test 7: Nested groups with /n
$str =~ /(?n:(aaa (bbb) aaa))/;
print "not " if defined $1 || defined $2;
say "ok # nested groups with (?n:) don't capture";

