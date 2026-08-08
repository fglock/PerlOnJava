use strict;
use warnings;
use Test::More tests => 12;

ok(' ' =~ /[\ ]/xx, 'escaped space remains literal under /xx');
ok(' ' =~ /[ \  ]/xx, 'unescaped spaces are ignored around escaped space');
ok('-' =~ /[ - ]/xx, 'hyphen remains literal after ignored spaces');
ok(' ' =~ /[ -\ ]/xx, 'escaped space can be a range endpoint');
ok("\t" =~ /[\	]/xx, 'escaped literal tab remains under /xx');
ok('-' =~ /[\	-	]/xx, 'hyphen remains literal beside ignored and escaped tabs');

ok(' ' =~ /[   ^   - ]/xx, 'leading ignored whitespace preserves class negation');
ok("\t" =~ /[   ^   - ]/xx, 'negated class includes tab');
ok('-' !~ /[   ^   - ]/xx, 'negated class excludes hyphen');
ok(' ' !~ /[   ^   \ - ]/xx, 'negated class excludes escaped space');
ok("\t" !~ /[   ^   \	- ]/xx, 'negated class excludes escaped tab');
ok('-' =~ /[   ^   \ -\ ]/xx, 'negated class includes hyphen between escaped spaces');
