use strict;
use warnings;

use Test::More tests => 4;

my $value = 'fooBar';
$value =~ s{(\w)([A-Z])}{$1_\L$2}g;
is($value, 'foo_bar', 'underscore after a capture is literal in a replacement');

'abc' =~ /(a)/;
is("$1_", 'a_', 'underscore after a capture is literal in a string');
is("$1_000", 'a_000', 'underscore and digits remain literal after a capture');
is("${1}_", 'a_', 'braced capture interpolation remains supported');
