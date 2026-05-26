use strict;
use warnings;
use Test::More;

my $v = version->parse('5.14.0');

is(ref($v), 'version', 'version->parse works before use version');
is("$v", '5.14.0', 'version object stringifies before use version');
ok(version->parse($^V) >= version->parse('5.14.0'), 'version comparison works before use version');

done_testing();
