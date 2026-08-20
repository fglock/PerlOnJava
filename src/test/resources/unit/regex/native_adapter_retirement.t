use strict;
use warnings;
use Test::More tests => 10;

our $REGMARK;

ok('a' =~ /(*:seen)a/, 'abbreviated MARK matches');
is($REGMARK, 'seen', 'abbreviated MARK publishes its name');
ok('[' =~ /[a[]/, 'nested opening bracket is a literal class member');
ok('a' =~ /[a[]/, 'ordinary member survives a literal opening bracket');
ok(' ' !~ /[a b]/xx, '/xx ignores unescaped horizontal class whitespace');
ok(' ' =~ /[a\ b]/xx, '/xx preserves escaped class whitespace');
ok(' ' !~ /(?xx:[a b])/, 'scoped /xx ignores class whitespace');
ok(' ' =~ /(?x:[a b])/, 'scoped /x preserves class whitespace');
ok(' ' =~ /(?xx:(?-x:[a b]))/, 'nested -x restores class whitespace');
ok('a' =~ /(?)a/, 'empty group is zero width');
