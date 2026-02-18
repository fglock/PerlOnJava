use v5.10;
use Test::More;

# Test 1: The original failing case from the bug report
my $rx = q{/(.*?)\{(.*?)\}/g};
my $i = 0;
my $input = "a{b}c{d}";
eval qq{while (eval \$input =~ $rx) { \$i++; last if \$i > 5; }};
is($i, 2, "Interpolated pattern with escaped braces in eval STRING");

# Test 2: Direct unescaped braces (should work with warning)
$input = "a{b}c{d}";
my $count = 0;
while ($input =~ /(.*?){(.*?)}/g) { $count++; last if $count > 5; }
is($count, 2, "Unescaped braces treated as literals");

# Test 3: Valid quantifiers still work
ok("abbb" =~ /ab{3}/, "Valid quantifier {3}");
ok("abbb" =~ /ab{2,4}/, "Valid quantifier {2,4}");
ok("abbbbbb" =~ /ab{2,}/, "Valid quantifier {2,}");

# Test 4: Mixed valid and invalid braces
my $mixed = "x{y}zzz";
ok($mixed =~ /x{y}z{3}/, "Mixed valid and invalid braces");

# Test 5: Character class braces (always literal)
ok("a{3}" =~ /[a{3}]+/, "Braces in character class are literal");

# Test 6: Empty braces (invalid quantifier)
ok("x{}y" =~ /x{}y/, "Empty braces treated as literal");

# Test 7: Non-numeric quantifier content
ok("x{abc}y" =~ /x{abc}y/, "Non-numeric braces treated as literal");

done_testing();