#!/usr/bin/perl
# Regression test for issue #1683: Scalar-List-Utils isvstring.t fails
# the dotted-num assertion.
#
# Also regression test for op/override.t test 7: require v5.6 with
# CORE::GLOBAL::require override should receive a value that numifies as ~5.006.

use strict;
use warnings;

$| = 1;
use Test::More tests => 13;
use Scalar::Util qw(isvstring);

# The override must be installed as a BEGIN block *after* all use statements
# so that it doesn't intercept Test::More or Scalar::Util loading.
# It intercepts only the runtime 'require v5.6' call below.
my $req_arg;
BEGIN { *CORE::GLOBAL::require = sub { $req_arg = shift; 1 } }

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
# Note: In Perl, a single integer literal is not a v-string
my $num = 49;
ok(!isvstring($num), 'single number is not a vstring');

# Tests 8-9: require v5.6 with CORE::GLOBAL::require override.
# Regression for op/override.t: Perl pre-caches NV=5.006 in the SV when
# compiling 'require v5.6', so the override receives a value that
# both stringifies as "\x05\x06" and numifies as ~5.006.
require v5.6;
ok($req_arg eq "\x05\x06", 'require v5.6 override: arg stringifies as \x05\x06');
ok(abs($req_arg - 5.006) < 0.001, 'require v5.6 override: arg numifies as ~5.006');

# Tests 10-11: Printable-start v-strings use their string content for numification.
# v49.46.48 stores bytes [49,46,48] = "1.0", which should numify as 1.0.
my $v_printable = v49.46.48;
ok(isvstring($v_printable), 'v49.46.48 is a vstring');
ok($v_printable == 1.0, 'printable-start vstring v49.46.48 numifies as 1.0');
