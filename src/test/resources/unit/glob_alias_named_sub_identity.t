use strict;
use warnings;
use Test::More;

*foo = \&baz;
*bar = *foo;
eval 'sub bar { (caller 0)[3] }';

is($@, '', 'a named sub can replace an aliased glob CODE slot');
is(bar(), 'main::foo', 'caller reports the canonical glob name after redefinition');

my $sub = sub { 4 };
*foo = $sub;
*bar = *foo;
undef &$sub;
eval 'sub bar { (caller 0)[3] }';

is($@, '', 'a named sub can replace an aliased anonymous CODE slot');
is(&$sub, 'main::foo', 'the original coderef follows the canonical glob slot');

done_testing;
