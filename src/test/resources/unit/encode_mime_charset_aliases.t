use strict;
use warnings;

use Encode ();
use Test::More;

my $ascii = Encode::find_encoding('ANSI X3.4-1968');
ok(defined($ascii), 'ANSI X3.4-1968 resolves to an encoding');
is($ascii ? $ascii->name : undef, 'ascii', 'ANSI X3.4-1968 uses Perl Encode ASCII name');

my $hz = Encode::find_encoding('HZ-GB-2312');
ok(defined($hz), 'HZ-GB-2312 resolves to an encoding');
is($hz ? $hz->name : undef, 'euc-cn', 'HZ-GB-2312 follows Perl Encode alias mapping');

my $hz_codec = Encode::find_encoding('hz');
ok(defined($hz_codec), 'the HZ codec resolves separately from EUC-CN');
my $hz_sample = '~{<:Ky2;S{#,NpJ)l6HK!#~}~';
my $hz_text = $hz_codec ? $hz_codec->decode($hz_sample) : '';
is(join(',', unpack('U*', $hz_text)), '24049,25152,19981,27442,65292,21247,26045,26044,20154,12290',
    'the HZ codec decodes shifted GB2312 bytes');
is($hz_codec ? $hz_codec->encode($hz_text) : undef, '~{<:Ky2;S{#,NpJ)l6HK!#~}',
    'the HZ codec encodes the shifted GB2312 sequence');

my $iso_8859_8_i = Encode::find_encoding('ISO-8859-8-I');
ok(defined($iso_8859_8_i), 'ISO-8859-8-I resolves to an encoding');

my $utf32 = Encode::find_encoding('UTF-32');
ok(defined($utf32), 'UTF-32 resolves to an encoding');
is($utf32 ? unpack('H*', $utf32->encode("\x{12345}")) : undef, '0000feff00012345',
    'UTF-32 encoding includes the Perl byte-order mark');

my $utf32be = Encode::find_encoding('UTF-32BE');
is($utf32be ? unpack('H*', $utf32be->encode("\x{12345}")) : undef, '00012345',
    'UTF-32BE encoding has no byte-order mark');

my $utf32le = Encode::find_encoding('UTF-32LE');
is($utf32le ? unpack('H*', $utf32le->encode("\x{12345}")) : undef, '45230100',
    'UTF-32LE encoding has no byte-order mark');

done_testing;
