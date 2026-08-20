use strict;
use warnings;
use utf8;
use Test::More;

my $upper = chr 0xC0;
my $lower = chr 0xE0;
my $upper_bytes = $upper;
my $lower_bytes = $lower;
utf8::downgrade($upper_bytes);
utf8::downgrade($lower_bytes);
my $upper_utf8 = $upper;
my $lower_utf8 = $lower;
utf8::upgrade($upper_utf8);
utf8::upgrade($lower_utf8);

ok($lower_bytes =~ qr/(?u:\xC0)/i, 'scoped u folds byte-source C0 forward');
ok($upper_bytes =~ qr/(?u:\xE0)/i, 'scoped u folds byte-source E0 reverse');
ok($lower_utf8 =~ qr/(?u:\xC0)/i, 'scoped u folds UTF-8 subject forward');
ok($upper_utf8 =~ qr/(?u:\xE0)/i, 'scoped u folds UTF-8 subject reverse');
ok($lower_bytes =~ qr/\xC0/iu, 'top-level u folds byte-source C0');
ok($upper_bytes =~ qr/\xE0/iu, 'top-level u folds byte-source E0');

ok($lower_bytes !~ qr/(?d:\xC0)/i, 'scoped d keeps Latin-1 byte fold disabled');
ok($lower_bytes =~ qr/(?a:\xC0)/i, 'scoped a retains Latin-1 byte folding');
ok($lower_bytes =~ qr/(?aa:\xC0)/i, 'scoped aa retains Latin-1 byte folding');

ok(chr(0xD7) =~ qr/(?u:\xD7)/i, 'multiplication sign remains literal under u fold');
ok(chr(0xF7) =~ qr/(?u:\xF7)/i, 'division sign remains literal under u fold');
ok(chr(0xD7) !~ qr/(?u:\xF7)/i, 'D7 and F7 do not fold together');
ok(chr(0xDF) =~ qr/(?u:\xDF)/i, 'sharp s remains a direct scalar match');

done_testing;
