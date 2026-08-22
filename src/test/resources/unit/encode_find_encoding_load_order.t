use strict;
use warnings;

use Test::More tests => 3;

require Encode;

my $utf8 = Encode::find_encoding('UTF-8');
ok(defined($utf8), 'find_encoding resolves UTF-8 after runtime module loading');
isa_ok($utf8, 'Encode::Encoding');
is($utf8->decode('abc'), 'abc', 'resolved encoding object decodes text');
