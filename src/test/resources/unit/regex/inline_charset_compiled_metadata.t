use strict;
use warnings;
use Test::More tests => 8;

my $upper = "\xC0";
my $lower = "\xE0";

ok(!utf8::is_utf8($upper), 'upper subject is byte-backed');
ok(!utf8::is_utf8($lower), 'lower subject is byte-backed');
ok($lower !~ qr/$upper/i, 'byte default suppresses Latin-1 case fold');
ok($lower !~ qr/$upper(?# (?u:commented)/i,
    'commented Unicode modifier does not change byte folding');
ok($lower =~ qr/(?u:$upper)/i, 'scoped Unicode modifier enables Latin-1 fold');
ok($lower =~ qr/(?a:$upper)/i, 'scoped ASCII modifier keeps non-ASCII fold');
ok($lower =~ qr/(?aa:$upper)/i, 'scoped strict ASCII keeps non-ASCII fold');
ok($lower !~ qr/\Q(?u:$upper)\E/i,
    'quoted modifier spelling remains literal');
