use strict;
use warnings;
use utf8;

use MIME::QuotedPrint qw(encode_qp decode_qp);
use Test::More;

ok defined(&MIME::Base64::encode_base64),
    'loading MIME::QuotedPrint also loads MIME::Base64';

my $encoded = encode_qp("märkøv\n");
ok(!utf8::is_utf8($encoded), 'quoted-printable encoding returns octets');
is($encoded, "m=E4rk=F8v\n", 'character values are encoded byte-for-byte');

my $decoded = decode_qp($encoded);
ok(!utf8::is_utf8($decoded), 'quoted-printable decoding returns octets');

done_testing;
