use strict;
use warnings;
use utf8;

use Test::More;
use Unicode::GCString;

my $value = Unicode::GCString->new("€éöyzz");
my $removed = $value->substr(5, 1, '');
is($removed->as_string, 'z', 'substr returns the removed grapheme cluster');
is($value->as_string, "€éöyz", 'empty replacement mutates the source object');
is($value->length, 5, 'replacement refreshes grapheme-cluster length');

my $middle = $value->substr(1, 2, "a\x{0301}");
is($middle->as_string, 'éö', 'multi-cluster removed substring is returned');
is($value->as_string, "€a\x{0301}yz", 'replacement accepts a combining grapheme');
is($value->length, 4, 'combining sequence counts as one grapheme cluster');

my $empty = $value->substr(2, 0, 'Q');
is($empty->as_string, '', 'zero-length substring returns an empty object');
is($value->as_string, "€a\x{0301}Qyz", 'zero-length replacement inserts at offset');

done_testing;
