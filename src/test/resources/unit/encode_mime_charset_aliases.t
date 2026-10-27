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

my $iso_8859_8_i = Encode::find_encoding('ISO-8859-8-I');
ok(defined($iso_8859_8_i), 'ISO-8859-8-I resolves to an encoding');

my $utf32 = Encode::find_encoding('UTF-32');
ok(defined($utf32), 'UTF-32 resolves to an encoding');
is($utf32 ? unpack('H*', $utf32->encode("\x{12345}")) : undef, '0000feff00012345',
    'UTF-32 encoding includes the Perl byte-order mark');

done_testing;
