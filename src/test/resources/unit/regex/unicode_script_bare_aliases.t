use strict;
use warnings;
use utf8;
use Test::More tests => 12;

ok("A" =~ /\p{islatin}/, 'lowercase Is-long script alias');
ok("A" =~ /\p{__Is_Latn}/, 'leading separators and short script alias');
ok("\x{391}" !~ /\p{islatin}/, 'script alias rejects nonmember');
ok("\x{391}" =~ /\p{^islatin}/, 'inner caret complements script alias');
ok("\x{391}" =~ /\P{islatin}/, 'P complements script alias');
ok("A" =~ /\P{^islatin}/, 'P and inner caret cancel');
ok("A" =~ /\p{latin}/, 'unprefixed long script alias control');
ok("A" =~ /\p{latn}/, 'unprefixed short script alias control');
ok("\x{30FC}" =~ /\p{ishiragana}/,
   'bare Is script alias uses Script_Extensions');
ok("\x{30FC}" =~ /\p{iskana}/, 'second Script_Extensions membership');
ok("\x{30FC}" !~ /\p{sc=Hira}/,
   'ordinary Script differs from bare shortcut');
my $invalid = eval q{qr/\p{is_sc=Latin}/};
ok(!$invalid && $@, 'lowercase Is assignment remains invalid');
