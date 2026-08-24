use strict;
use warnings;
use utf8;
use Test::More;

no warnings 'experimental::regex_sets';

my $sharp_s = qr/\A(?[ [\x{00df}] ])\z/iu;
ok("\x{00df}" =~ $sharp_s, 'extended class matches its sharp-s member');
ok("\x{1e9e}" =~ $sharp_s, 'extended class permits the simple capital sibling');
ok('ss' !~ $sharp_s, 'extended class does not match a two-character fold');

my $ligature = qr/\A(?[ [\x{fb03}] ])\z/iu;
ok("\x{fb03}" =~ $ligature, 'extended class matches its ligature member');
ok('ffi' !~ $ligature, 'extended class does not match a three-character fold');

my $ordinary = qr/\A\x{00df}\z/iu;
ok('ss' =~ $ordinary, 'ordinary regex still supports the sharp-s multifold');

done_testing;
