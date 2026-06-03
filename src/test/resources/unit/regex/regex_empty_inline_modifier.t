use strict;
use warnings;
use Test::More tests => 4;

ok('Big Market' =~ qr/(?)Market/, 'empty inline modifier group is a no-op');

my $modifiers = '';
my $regex = 'Market';
ok('Big Market' =~ qr/(?$modifiers)$regex/, 'interpolated empty inline modifier group is a no-op');

ok('Big Market' =~ qr/(?i)market/, 'non-empty inline modifier group still applies');
ok('Big Market' =~ qr/(?:Big)\s+(?)Market/x, 'empty inline modifier works inside extended regex');
