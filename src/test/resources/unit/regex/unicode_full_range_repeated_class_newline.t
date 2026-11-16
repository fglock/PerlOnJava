use strict;
use warnings;
use Test::More tests => 8;

my $text = "a\nb";

ok("\n" =~ /[\x{0}-\x{7fffffff}]/,
    'the explicit full scalar range contains a newline');
ok($text =~ /\A[\x{0}-\x{7fffffff}]*\z/,
    'repeating the explicit full scalar range matches across a newline');
ok($text =~ /\A([\x{0}-\x{7fffffff}]*)\z/,
    'capture does not change repeated full-range class membership');
is($1, $text, 'repeated full-range capture includes the newline');
ok($text =~ /\A[\x{0}-\x{10ffff}]*\z/,
    'the Unicode-bounded full range remains a positive control');
ok("a\nb" !~ /\A[^\x{0}-\x{7fffffff}]*\z/,
    'negating the full scalar range remains empty for Unicode characters');
ok("\n" !~ /\A[\x{0}-\x{9}]\z/,
    'a class ending before newline still excludes it');
ok($text =~ /\A[\x{0}-\x{7fffffff}]*\z/s,
    'dot-all does not change explicit full-range class membership');
