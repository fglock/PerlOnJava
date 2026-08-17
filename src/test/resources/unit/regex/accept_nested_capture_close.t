use strict;
use warnings;
use Test::More tests => 28;

$_ = 'aced';

ok(/((a?(*ACCEPT)())())()/, 'nested ACCEPT matches');
is($1, 'a', 'ACCEPT closes outer capture 1');
is($2, 'a', 'ACCEPT closes outer capture 2');
ok(!defined($3), 'ACCEPT leaves following capture 3 undefined');
ok(!defined($4), 'ACCEPT leaves following capture 4 undefined');
ok(!defined($5), 'ACCEPT leaves following capture 5 undefined');
ok(!defined($6), 'ACCEPT leaves following capture 6 undefined');

ok(/((a?())())()/, 'equivalent pattern without ACCEPT matches');
is($1, 'a', 'ordinary close captures group 1');
is($2, 'a', 'ordinary close captures group 2');
is($3, '', 'ordinary close captures group 3');
is($4, '', 'ordinary close captures group 4');
is($5, '', 'ordinary close captures group 5');
ok(!defined($6), 'ordinary close leaves group 6 undefined');

ok(/((a?(*ACCEPT)(c))(e))(d)/, 'ACCEPT bypasses required suffixes');
is($1, 'a', 'suffix-bypassing ACCEPT closes capture 1');
is($2, 'a', 'suffix-bypassing ACCEPT closes capture 2');
ok(!defined($3), 'bypassed capture 3 is undefined');
ok(!defined($4), 'bypassed capture 4 is undefined');
ok(!defined($5), 'bypassed capture 5 is undefined');
ok(!defined($6), 'capture 6 is undefined');

ok(/((a?(c))(e))(d)/, 'equivalent required suffix pattern matches');
is($1, 'ace', 'ordinary suffix pattern captures group 1');
is($2, 'ac', 'ordinary suffix pattern captures group 2');
is($3, 'c', 'ordinary suffix pattern captures group 3');
is($4, 'e', 'ordinary suffix pattern captures group 4');
is($5, 'd', 'ordinary suffix pattern captures group 5');
ok(!defined($6), 'ordinary suffix pattern leaves group 6 undefined');
