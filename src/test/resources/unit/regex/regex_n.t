use strict;
use Test::More;
use feature 'say';

my $str = "aaa bbb aaa";

# Test 1: Normal capturing without /n
$str =~ /(aaa)/;
ok(!($1 ne "aaa"), 'normal capturing works');

# Test 2: Disable capturing with (?n) inline
$str =~ /(?n)(aaa)/;
ok(!(defined $1), '(?n) disables capturing');

# Test 3: Disable capturing with (?n:...) group syntax
$str =~ /(?n:(aaa))/;
ok(!(defined $1), '(?n:pattern) disables capturing');

# Test 4: Mixed capturing - outside enabled, inside disabled
$str =~ /((?n:aaa) bbb)/;
print "not " unless ($1 eq "aaa bbb");
ok(!(defined $2), 'mixed capturing works');

# Test 5: Turn off /n with (?^)
$str =~ /(?n)(aaa) (?^)(bbb)/;
print "not " if defined $2;
ok(!($1 ne "bbb"), '(?^) restores capturing ($1)($2)');

# Test 6: Multiple groups with /n
$str =~ /(?n)(aaa) (bbb) (aaa)/;
ok(!(defined $1 || defined $2 || defined $3), 'multiple groups with (?n) don\'t capture');

# Test 7: Nested groups with /n
$str =~ /(?n:(aaa (bbb) aaa))/;
ok(!(defined $1 || defined $2), 'nested groups with (?n:) don\'t capture');

done_testing();
