use strict;
use warnings;
use charnames ':full';
use Test::More tests => 4;

my $class_match = eval q{
    my $re1 = "\N{WHITE SMILING FACE}";
    my $e_grave = chr utf8::unicode_to_native(0xE8);
    $e_grave =~ qr/[\w$re1]/;
};
ok($class_match,
    'interpolated named character keeps Unicode character-class matching');

my $alternation_match = eval q{
    my $re2 = "\N{WHITE SMILING FACE}";
    my $e_grave = chr utf8::unicode_to_native(0xE8);
    $e_grave =~ qr/\w|$re2/;
};
ok($alternation_match,
    'interpolated named character keeps Unicode alternation matching');
my $smile_match = eval q{
    my $smile = "\N{WHITE SMILING FACE}";
    $smile =~ qr/[\w$smile]/;
};
ok($smile_match,
    'interpolated named character matches its own character class');
my $punctuation_match = eval q{
    my $smile = "\N{WHITE SMILING FACE}";
    '!' =~ qr/[\w$smile]/;
};
ok(!$punctuation_match,
    'interpolated named character class rejects unrelated punctuation');
