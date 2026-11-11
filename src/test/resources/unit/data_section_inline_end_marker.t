use strict;
use Test::More tests => 1;

pass('__END__ after code on the same line terminates the source');
map{s[][];eval;0}<DATA>__END__
()x0
