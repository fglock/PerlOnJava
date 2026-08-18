use strict;
use warnings;
use utf8;
use Test::More tests => 14;
no warnings 'experimental::uniprop_wildcards';

my $unicode_16 = chr 0x1FBEF;

ok($unicode_16 =~ /\p{Age=v160}/, 'compact V age alias');
ok($unicode_16 =~ /\p{Age= +0000016.0}/, 'loose plus and leading zero age alias');
ok($unicode_16 =~ /\p{Age=-V16_0}/, 'loose hyphen and underscore age alias');
ok($unicode_16 =~ /\p{Is_Age=0000016.0}/, 'Is_Age accepts a loose numeric value');
ok($unicode_16 =~ /\p{In=v160}/, 'In accepts a compact V value');
ok($unicode_16 =~ /\p{Present_In=16.0}/, 'Present_In includes the introduction version');
ok('A' =~ /\p{In=16.0}/, 'In is cumulative across older versions');
ok('A' !~ /\p{Age=16.0}/, 'Age remains an exact introduction version');
ok($unicode_16 =~ /\p{Age=:\AV16_0\z:}/,
   'anchored Unicode property wildcard resolves a long age value');
ok($unicode_16 =~ /\p{Age=:\Av160\z:}/,
   'anchored Unicode property wildcard resolves a compact age value');
ok($unicode_16 =~ /\p{Age: v160}/, 'colon delimits an Age property value');
ok('A' =~ /\p{Present_In: 16.0}/,
   'colon delimits a cumulative Present_In value');
ok(chr(0x3347A) =~ /\p{Age=Unassigned}/,
   'Unassigned uses the pinned complement');
ok(chr(0x3347A) =~ /\p{Is_Age=NA}/, 'NA aliases the unassigned age value');
