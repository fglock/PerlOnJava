use strict;
use warnings;
use Test::More;
use Encode qw(decode find_encoding);
use utf8;

my $codec = find_encoding('MIME-Header');
ok(defined $codec, 'MIME-Header codec is autoloaded by find_encoding');

is(
    decode('MIME-Header', '=?UTF-8?B?RMO2eQ==?= <test@example.com>'),
    'Döy <test@example.com>',
    'decodes a UTF-8 base64 encoded display name',
);

is(
    decode('MIME-Header', '=?ISO-8859-1?Q?Andr=E9?= =?UTF-8?B?IM6gzrXPgc6zzr8=?='),
    'André Περγο',
    'decodes adjacent quoted-printable and base64 encoded words',
);

done_testing;
