use strict;
use warnings;
use Test::More tests => 10;

my $nbsp = chr 0xA0;
ok($nbsp !~ qr/(?d:\s)/, 'byte NBSP is not whitespace under /d');
ok($nbsp =~ qr/(?d:\S)/, 'byte NBSP is non-whitespace under /d');
ok($nbsp !~ qr/(?d:[_[:space:]])/, 'byte NBSP is not POSIX space under /d');
ok($nbsp =~ qr/(?d:[_[:^space:]])/, 'byte NBSP is POSIX non-space under /d');

utf8::upgrade($nbsp);
ok($nbsp =~ qr/(?d:\s)/, 'upgraded NBSP is whitespace under /d');
ok($nbsp =~ qr/(?d:[_[:space:]])/, 'upgraded NBSP is POSIX space under /d');

my $fullwidth_five = chr 0xFF15;
ok($fullwidth_five =~ qr/(?d:[_[:xdigit:]])/, 'fullwidth digit is POSIX xdigit under /d');
ok($fullwidth_five !~ qr/(?d:[_[:^xdigit:]])/, 'fullwidth digit is not POSIX non-xdigit under /d');
ok($fullwidth_five =~ qr/(?u:[_[:xdigit:]])/, 'fullwidth digit is POSIX xdigit under /u');
ok($fullwidth_five !~ qr/(?u:[_[:^xdigit:]])/, 'fullwidth digit is not POSIX non-xdigit under /u');
