use strict;
use warnings;
use Test::More;

# Test basic integer parsing
my $int = 42;
is($int, 42, 'Basic integer parsing');

# Test negative integer parsing
my $neg_int = -42;
is($neg_int, -42, 'Negative integer parsing');

# Test floating point parsing
my $float = 3.14;
is($float, 3.14, 'Floating point parsing');

# Test negative floating point parsing
my $neg_float = -3.14;
is($neg_float, -3.14, 'Negative floating point parsing');

# Test hexadecimal parsing
my $hex = 0xFF;
is($hex, 255, "Hexadecimal parsing <$hex>");

# Test hexadecimal parsing with uppercase letters
my $hex_upper = 0x1A;
is($hex_upper, 26, "Hexadecimal parsing with uppercase <$hex_upper>");

# Test octal parsing
my $octal = 0755;
is($octal, 493, 'Octal parsing');

# Test binary parsing
my $binary = 0b1010;
is($binary, 10, 'Binary parsing');

# Test scientific notation
my $sci = 1.5e3;
is($sci, 1500, "Scientific notation parsing <$sci>");

# Test hexadecimal floating point parsing
my $hex_float = 0x1.999ap-4;
is(sprintf("%.6f", $hex_float), sprintf("%.6f", 0.1), 'Hexadecimal floating point parsing');

# Test hexadecimal floating point parsing with uppercase P
my $hex_float_upper = 0x1.999AP-4;
is(sprintf("%.6f", $hex_float_upper), sprintf("%.6f", 0.1), 'Hexadecimal floating point parsing with uppercase P');

# Test zero
my $zero = 0;
is($zero, 0, 'Zero parsing');

# Test very large number
my $large_num = 1e308;
is($large_num, 1e308, 'Very large number parsing');

# Test very small number
my $small_num = 1e-308;
is($small_num, 1e-308, 'Very small number parsing');

done_testing();

