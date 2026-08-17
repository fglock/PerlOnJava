use strict;
use warnings;
use utf8;
use Test::More tests => 9;

my $euro = chr 0x20AC;
my $grin = chr 0x1F600;

ok($euro =~ /\p{Age=2.1}/, 'Age matches the exact introduction version');
ok($euro !~ /\p{Age=3.0}/, 'Age excludes characters introduced earlier');
ok($euro =~ /\p{In=3.0}/, 'In includes characters present by the version');
ok($euro =~ /\p{Present_In=3.0}/, 'Present_In aliases cumulative In');
ok($euro =~ /\p{PresentIn=V30}/, 'loose PresentIn and V version aliases work');
ok($grin =~ /\p{Age=6.1}/, 'later exact Age values match');
ok($grin !~ /\p{In=6.0}/, 'In excludes later introductions');
ok($grin =~ /\p{In=6.1}/, 'In includes the introduction version');
ok($grin =~ /\P{Age=6.0}/, 'negated exact Age uses the Perl set');
