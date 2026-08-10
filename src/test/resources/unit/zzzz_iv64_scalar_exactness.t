use strict;
use warnings;

use Test::More tests => 10;

is(9007199254740991, '9007199254740991', '2^53 - 1 stringifies exactly');
is(9007199254740992, '9007199254740992', '2^53 stringifies exactly');
is(9223372036854775807, '9223372036854775807', 'IV_MAX stringifies exactly');
is(-9223372036854775807 - 1, '-9223372036854775808',
    'IV_MIN stringifies exactly');

is(4503599627370495 + 1, '4503599627370496',
    'addition remains exact above 32 bits');
is(4294967296 - 1, '4294967295', 'subtraction remains exact above 32 bits');
is(2147483648 * 2, '4294967296', 'multiplication remains exact above 32 bits');
ok(9007199254740992 > 9007199254740991,
    'adjacent large integers compare distinctly');

my $increment = 9007199254740991;
is(++$increment, '9007199254740992', 'preincrement remains exact');
is($increment++, '9007199254740992', 'postincrement returns exact old value');
