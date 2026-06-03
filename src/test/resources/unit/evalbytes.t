use strict;
use warnings;
use Test::More tests => 3;
use feature qw(evalbytes unicode_eval);

my $u100_utf8 = "\xc4\x80";
is(evalbytes("no strict 'subs'; use utf8; $u100_utf8"), chr 256, 'evalbytes decodes source after use utf8');

my $upcode = "no strict 'subs'; use utf8; $u100_utf8" . chr 256;
chop $upcode;
is(evalbytes($upcode), chr 256, 'evalbytes decodes upgraded byte source after use utf8');

eval { evalbytes chr 256 };
like($@, qr/Wide character/, 'evalbytes rejects non-byte characters');
