use strict;
use warnings;
use Test::More;

my $cipher_text = "\xa4\x2d\x82";
my $translated = $cipher_text =~ tr/\xa4\x2d\x82/ABC/;
is($translated, 3, 'transliteration counts escaped byte characters');
is($cipher_text, 'ABC', 'escaped hexadecimal dash stays a literal transliteration character');

done_testing();
