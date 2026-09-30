use strict;
use warnings;
use Test::More tests => 4;

my $text = '+,-';
$text =~ tr/-+,/ab\-/;
is($text, 'b-a', 'a hyphen at the start of a transliteration list is literal');

$text = '+,-';
$text =~ tr/+\--/a\/c/;
is($text, 'a,/', 'escaped delimiters and hyphens stay literal in transliteration lists');

$text = 'abc';
$text =~ tr/abc/xyz/;
is($text, 'xyz', 'ordinary transliteration ranges still map left to right');

$text = 'xyz';
$text =~ tr/xyz/abc/;
is($text, 'abc', 'the inverse transliteration mapping remains symmetric');
