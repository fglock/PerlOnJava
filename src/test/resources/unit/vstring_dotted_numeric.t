#!/usr/bin/perl
# Regression test for issue #1683: Scalar-List-Utils isvstring.t fails
# the dotted-num assertion
#
# This test verifies that dotted numeric literals (bareword v-strings without
# the 'v' prefix) are correctly parsed as v-strings and have the VSTRING type.

use strict;
use warnings;

$| = 1;
use Test::More tests => 9;
use Scalar::Util qw(isvstring);

# Test 1: Basic dotted numeric literal
my $vs1 = 49.46.48;
ok($vs1 == "1.0", 'dotted numeric literal equals "1.0"');
ok(isvstring($vs1), 'dotted numeric literal is a vstring');

# Test 2: Explicit v-string should also work
my $vs2 = v49.46.48;
ok($vs2 == $vs1, 'explicit v-string equals dotted numeric literal');
ok(isvstring($vs2), 'explicit v-string is a vstring');

# Test 3: Multi-part v-strings
my $vs3 = 1.2.3;
ok(isvstring($vs3), 'three-part dotted numeric is a vstring');

# Test 4: String should not be a vstring
my $str = "1.0";
ok(!isvstring($str), 'plain string is not a vstring');

# Test 5: Regular float should not be a vstring
my $float = 1.5;
ok(!isvstring($float), 'regular float is not a vstring');

# Test 6: Large v-string components
my $vs4 = 192.168.1.1;
ok(isvstring($vs4), 'large component dotted numeric is a vstring');

# Test 7: Single component (edge case - should still work)
# Note: In Perl, even "1" without a dot is technically not a v-string
my $num = 49;
ok(!isvstring($num), 'single number is not a vstring');
