use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 18;
use Net::Pcap;

my ($classic_fh, $classic_path) = tempfile();
binmode($classic_fh);
my $classic_packet = "\x01\x02\x03\x04";
print {$classic_fh} pack('a4vvVVVV', "\xd4\xc3\xb2\xa1", 2, 4, 0, 0, 65535, 1);
print {$classic_fh} pack('VVVVa*', 10, 250, 4, 4, $classic_packet);
close($classic_fh);

my $error = '';
my $pcap = Net::Pcap::open_offline($classic_path, \$error);
isa_ok($pcap, 'pcap_tPtr');
is($error, '', 'classic capture opens without an error');
is(Net::Pcap::datalink($pcap), DLT_EN10MB, 'classic link type is read');
is(Net::Pcap::snapshot($pcap), 65535, 'classic snapshot length is read');
is(Net::Pcap::major_version($pcap), 2, 'classic major version is read');

my %header;
is(Net::Pcap::next($pcap, \%header), $classic_packet, 'classic packet bytes are read');
is_deeply(\%header, { tv_sec => 10, tv_usec => 250, caplen => 4, len => 4 },
    'classic packet metadata is read');
ok(!defined Net::Pcap::next($pcap, \%header), 'classic end of capture returns undef');

my ($ng_fh, $ng_path) = tempfile();
binmode($ng_fh);
my $section = pack('VVa4vvVVV', 0x0a0d0d0a, 28, "\x4d\x3c\x2b\x1a",
    1, 0, 0xffffffff, 0xffffffff, 28);
my $interface = pack('VVvvVV', 1, 20, 1, 0, 65535, 20);
my $ng_packet = "ABCD";
my $enhanced = pack('VVVVVVVa4V', 6, 36, 0, 0, 500000, 4, 4, $ng_packet, 36);
print {$ng_fh} $section, $interface, $enhanced;
close($ng_fh);

$error = '';
$pcap = Net::Pcap::open_offline($ng_path, \$error);
isa_ok($pcap, 'pcap_tPtr');
is($error, '', 'pcapng capture opens without an error');
is(Net::Pcap::datalink($pcap), DLT_EN10MB, 'pcapng interface link type is read');

my $raw;
%header = ();
is(Net::Pcap::next_ex($pcap, \%header, \$raw), 1, 'pcapng next_ex reports a packet');
is($raw, $ng_packet, 'pcapng packet bytes are read');
is($header{tv_usec}, 500000, 'pcapng timestamp resolution defaults to microseconds');
is(Net::Pcap::next_ex($pcap, \%header, \$raw), -2, 'pcapng end of capture is reported');

my $filter;
is(Net::Pcap::compile($pcap, \$filter, '', 0, 0), 0, 'empty offline filter compiles');
is(Net::Pcap::setfilter($pcap, $filter), 0, 'offline filter can be installed');
like(Net::Pcap::lib_version(), qr/^libpcap version/, 'compatible version string is returned');

unlink($classic_path, $ng_path);
