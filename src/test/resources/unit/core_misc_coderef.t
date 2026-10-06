use strict;
use warnings;
use Test::More tests => 6;

my $prototype = \&CORE::prototype;
is($prototype->(\&CORE::index), prototype('CORE::index'),
    'CORE::prototype code reference reads a CORE prototype');

my $pos = \&CORE::pos;
my $text = 'gubai';
pos($text) = 3;
is($pos->(\$text), 3,
    'CORE::pos code reference reads a scalar position');
$pos->(\$text) = 4;
is(pos($text), 4,
    'CORE::pos code reference updates a scalar position');

my $scalar = \&CORE::scalar;
is($scalar->(3), 3, 'CORE::scalar code reference returns its argument');

my $select = \&CORE::select;
my $selected = select;
is($select->(), $selected,
    'CORE::select code reference returns the selected handle');

my $tell = \&CORE::tell;
is($tell->(), tell(), 'CORE::tell code reference uses the default handle');
