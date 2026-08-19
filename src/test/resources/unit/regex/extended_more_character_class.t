use strict;
use warnings;
use Test::More;

ok(' ' =~ /(?x:[a b])/xx, 'scoped single x downgrades outer xx');
ok(' ' !~ /(?xx:[a b])/x, 'scoped xx ignores class space');
ok(' ' =~ /(?x)[a b]/xx, 'option-only single x downgrades outer xx');
ok(' ' !~ /(?xx)[a b]/x, 'option-only xx ignores class space');
ok(' ' =~ /(?-x:[a b])/xx, 'scoped minus x disables both x levels');

ok("\t" !~ /(?xx:[a	b])/, 'xx ignores an unescaped class tab');
ok("\n" =~ /(?xx:[a
b])/, 'xx preserves an unescaped class newline');
ok('#' =~ /(?xx:[a#b])/, 'xx preserves a class hash');
ok(' ' =~ /(?xx:[a\ b])/, 'xx preserves escaped class space');

done_testing;
