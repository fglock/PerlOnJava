use strict;
use warnings;
use utf8;
use Test::More;

ok("A" =~ /[aA]/, 'mask pair accepts upper member');
ok("a" =~ /[aA]/, 'mask pair accepts lower member');
ok("C" !~ /[aA]/, 'mask pair rejects unrelated member');
ok("A" =~ /[[:ascii:]]/, 'ASCII pseudo-class accepts ASCII');
ok("\x{100}" !~ /[[:ascii:]]/, 'ASCII pseudo-class rejects wide scalar');
ok("\x{100}" =~ /[[:^ascii:]]/, 'negated ASCII accepts wide scalar');
ok("A" !~ /[[:^ascii:]]/, 'negated ASCII rejects ASCII');

ok("\x{c5}" =~ /[\x{c5}\x{e5}]/, 'generic low list accepts first');
ok("\x{e5}" =~ /[\x{c5}\x{e5}]/, 'generic low list accepts second');
ok("\n" =~ /[^\S ]/, 'whitespace-minus-space accepts newline');
ok(" " !~ /[^\S ]/, 'whitespace-minus-space rejects space');
ok("A" =~ /[^\n\r]/, 'literal complement accepts ordinary byte');
ok("\n" !~ /[^\n\r]/, 'literal complement rejects newline');
ok("_" =~ /[_[:blank:]]/, 'blank composite accepts underscore');
ok("\t" =~ /[_[:blank:]]/, 'blank composite accepts tab');
ok(" " =~ /[_[:blank:]]/, 'blank composite accepts space');

ok("\a" =~ /[\x{07}-\x{0b}]/, 'packed low range accepts bell');
ok("\x{0b}" =~ /[\x{07}-\x{0b}]/, 'packed low range accepts vertical tab');
ok("\f" !~ /[\x{07}-\x{0b}]/, 'packed low range rejects form feed');
ok("9" =~ /[0-9]/, 'POSIX-order control accepts digit');

my $locale = eval q{qr/(?li:[a-z])/};
ok(!$@ && "K" =~ $locale, 'locale folded range compiles and matches ASCII');
ok("\x{10ffff}" =~ /[\p{Any}]/, 'Any property accepts Unicode maximum');

done_testing;
