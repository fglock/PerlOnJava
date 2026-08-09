package Net::Pcap;

use strict;
use warnings;
use Carp qw(croak);
use Exporter 'import';

our $VERSION = '0.21';

use constant {
    PCAP_ERRBUF_SIZE => 256,
    PCAP_IF_LOOPBACK => 1,
    PCAP_VERSION_MAJOR => 2,
    PCAP_VERSION_MINOR => 4,
    DLT_NULL => 0,
    DLT_EN10MB => 1,
    DLT_PPP => 9,
    DLT_RAW => 12,
    DLT_IEEE802_11 => 105,
    DLT_LINUX_SLL => 113,
    DLT_IEEE802_11_RADIO => 127,
    DLT_ERF => 197,
};

my @FUNCTIONS = qw(
    lookupdev findalldevs lookupnet open_live open_dead open_offline loop
    breakloop close dispatch next next_ex compile compile_nopcap setfilter
    freecode offline_filter setnonblock getnonblock dump_open dump dump_file
    dump_flush dump_close datalink set_datalink datalink_name_to_val
    datalink_val_to_name datalink_val_to_description snapshot is_swapped
    major_version minor_version stats file fileno get_selectable_fd geterr
    strerror perror lib_version createsrcstr parsesrcstr open setbuff
    setuserbuffer setmode setmintocopy getevent sendpacket sendqueue_alloc
    sendqueue_queue sendqueue_transmit
);

our @EXPORT = (
    qw(PCAP_ERRBUF_SIZE PCAP_IF_LOOPBACK PCAP_VERSION_MAJOR PCAP_VERSION_MINOR
       DLT_NULL DLT_EN10MB DLT_PPP DLT_RAW DLT_IEEE802_11 DLT_LINUX_SLL
       DLT_IEEE802_11_RADIO DLT_ERF UNSAFE_SIGNALS),
    map { "pcap_$_" } @FUNCTIONS,
);
our @EXPORT_OK = (@FUNCTIONS, @EXPORT);
our %EXPORT_TAGS = (functions => [@FUNCTIONS]);

sub UNSAFE_SIGNALS (&) { $_[0]->() }

sub open_offline {
    croak 'Usage: Net::Pcap::open_offline(fname, err)'
        unless @_ == 2;
    my ($path, $error) = @_;
    croak 'arg2 not a reference' unless ref($error);
    $$error = '';

    my $fh;
    unless (CORE::open($fh, '<', $path)) {
        $$error = "$!";
        return;
    }
    binmode($fh);
    local $/;
    my $data = <$fh>;
    CORE::close($fh);

    my $pcap = eval { _parse_capture($data, $path) };
    if (!$pcap) {
        my $message = $@ || 'invalid or unsupported capture file';
        $message =~ s/\s+\z//;
        $$error = $message;
        return;
    }
    return $pcap;
}

sub _parse_capture {
    my ($data, $path) = @_;
    die "capture file is shorter than its header\n" unless length($data) >= 4;
    my $magic = substr($data, 0, 4);
    return _parse_pcapng($data, $path) if $magic eq "\x0a\x0d\x0d\x0a";
    return _parse_classic($data, $path);
}

sub _parse_classic {
    my ($data, $path) = @_;
    die "classic pcap header is truncated\n" unless length($data) >= 24;
    my $magic = substr($data, 0, 4);
    my ($little, $nanoseconds);
    if ($magic eq "\xd4\xc3\xb2\xa1") { ($little, $nanoseconds) = (1, 0) }
    elsif ($magic eq "\xa1\xb2\xc3\xd4") { ($little, $nanoseconds) = (0, 0) }
    elsif ($magic eq "\x4d\x3c\xb2\xa1") { ($little, $nanoseconds) = (1, 1) }
    elsif ($magic eq "\xa1\xb2\x3c\x4d") { ($little, $nanoseconds) = (0, 1) }
    else { die "unrecognized pcap magic\n" }

    my $major = _u16($data, 4, $little);
    my $minor = _u16($data, 6, $little);
    my $snaplen = _u32($data, 16, $little);
    my $linktype = _u32($data, 20, $little);
    my @packets;
    my $offset = 24;
    while ($offset < length($data)) {
        die "pcap packet header is truncated\n" if $offset + 16 > length($data);
        my $seconds = _u32($data, $offset, $little);
        my $fraction = _u32($data, $offset + 4, $little);
        my $captured = _u32($data, $offset + 8, $little);
        my $original = _u32($data, $offset + 12, $little);
        $offset += 16;
        die "pcap packet data is truncated\n" if $offset + $captured > length($data);
        push @packets, {
            raw => substr($data, $offset, $captured),
            tv_sec => $seconds,
            tv_usec => $nanoseconds ? int($fraction / 1000) : $fraction,
            caplen => $captured,
            len => $original,
        };
        $offset += $captured;
    }

    return bless {
        packets => \@packets, index => 0, path => $path, linktype => $linktype,
        snaplen => $snaplen, major => $major, minor => $minor,
        swapped => $little == _host_is_little() ? 0 : 1, error => '',
    }, 'pcap_tPtr';
}

sub _parse_pcapng {
    my ($data, $path) = @_;
    my (@packets, @interfaces);
    my ($little, $major, $minor) = (undef, 1, 0);
    my $offset = 0;

    while ($offset + 12 <= length($data)) {
        my $raw_type = substr($data, $offset, 4);
        if ($raw_type eq "\x0a\x0d\x0d\x0a") {
            die "pcapng section header is truncated\n" if $offset + 16 > length($data);
            my $bom = substr($data, $offset + 8, 4);
            $little = $bom eq "\x4d\x3c\x2b\x1a" ? 1
                    : $bom eq "\x1a\x2b\x3c\x4d" ? 0
                    : die "invalid pcapng byte-order magic\n";
        }
        die "pcapng block precedes section header\n" unless defined $little;
        my $type = _u32($data, $offset, $little);
        my $length = _u32($data, $offset + 4, $little);
        die "invalid pcapng block length\n"
            if $length < 12 || $offset + $length > length($data);
        die "mismatched pcapng block length\n"
            unless _u32($data, $offset + $length - 4, $little) == $length;

        if ($type == 0x0a0d0d0a) {
            $major = _u16($data, $offset + 12, $little);
            $minor = _u16($data, $offset + 14, $little);
            @interfaces = ();
        }
        elsif ($type == 1) {
            my $interface = {
                linktype => _u16($data, $offset + 8, $little),
                snaplen => _u32($data, $offset + 12, $little),
                resolution => 1000000,
            };
            my $option = $offset + 16;
            my $option_end = $offset + $length - 4;
            while ($option + 4 <= $option_end) {
                my $code = _u16($data, $option, $little);
                my $size = _u16($data, $option + 2, $little);
                last if $code == 0;
                if ($code == 9 && $size >= 1) {
                    my $power = ord(substr($data, $option + 4, 1));
                    $interface->{resolution} = $power & 0x80
                        ? 2 ** ($power & 0x7f) : 10 ** $power;
                }
                $option += 4 + (($size + 3) & ~3);
            }
            push @interfaces, $interface;
        }
        elsif ($type == 6) {
            my $id = _u32($data, $offset + 8, $little);
            my $hi = _u32($data, $offset + 12, $little);
            my $lo = _u32($data, $offset + 16, $little);
            my $captured = _u32($data, $offset + 20, $little);
            my $original = _u32($data, $offset + 24, $little);
            my $interface = $interfaces[$id] || { linktype => 1, resolution => 1000000 };
            _push_pcapng_packet(\@packets, $data, $offset + 28, $captured,
                $original, $hi, $lo, $interface);
        }
        elsif ($type == 2) {
            my $id = _u16($data, $offset + 8, $little);
            my $hi = _u32($data, $offset + 12, $little);
            my $lo = _u32($data, $offset + 16, $little);
            my $captured = _u32($data, $offset + 20, $little);
            my $original = _u32($data, $offset + 24, $little);
            my $interface = $interfaces[$id] || { linktype => 1, resolution => 1000000 };
            _push_pcapng_packet(\@packets, $data, $offset + 28, $captured,
                $original, $hi, $lo, $interface);
        }
        elsif ($type == 3) {
            my $original = _u32($data, $offset + 8, $little);
            my $available = $length - 16;
            my $interface = $interfaces[0] || { linktype => 1, snaplen => $available };
            my $captured = $original < $interface->{snaplen} ? $original : $interface->{snaplen};
            $captured = $available if $captured > $available;
            _push_pcapng_packet(\@packets, $data, $offset + 12, $captured,
                $original, 0, 0, $interface);
        }
        $offset += $length;
    }

    my $interface = $interfaces[0] || { linktype => 1, snaplen => 65535 };
    return bless {
        packets => \@packets, index => 0, path => $path,
        linktype => $interface->{linktype}, snaplen => $interface->{snaplen},
        major => $major, minor => $minor, swapped => 0, error => '',
    }, 'pcap_tPtr';
}

sub _push_pcapng_packet {
    my ($packets, $data, $start, $captured, $original, $hi, $lo, $interface) = @_;
    die "pcapng packet data is truncated\n" if $start + $captured > length($data);
    my $ticks = $hi * 4294967296 + $lo;
    my $resolution = $interface->{resolution} || 1000000;
    my $seconds = int($ticks / $resolution);
    my $microseconds = int(($ticks - $seconds * $resolution) * 1000000 / $resolution);
    push @$packets, {
        raw => substr($data, $start, $captured), tv_sec => $seconds,
        tv_usec => $microseconds, caplen => $captured, len => $original,
    };
}

sub next {
    croak 'Usage: Net::Pcap::next(p, pkt_header)' unless @_ == 2;
    my ($pcap, $header) = @_;
    croak 'p is not of type pcap_tPtr' unless ref($pcap) eq 'pcap_tPtr';
    croak 'arg2 not a hash ref' unless ref($header) eq 'HASH';
    return if $pcap->{closed} || $pcap->{index} >= @{$pcap->{packets}};
    my $packet = $pcap->{packets}->[$pcap->{index}++];
    for my $key (qw(tv_sec tv_usec caplen len)) { $header->{$key} = $packet->{$key} }
    return $packet->{raw};
}

sub next_ex {
    croak 'Usage: Net::Pcap::next_ex(p, pkt_header, pkt_data)' unless @_ == 3;
    my ($pcap, $header, $data) = @_;
    croak 'arg3 not a scalar ref' unless ref($data) eq 'SCALAR';
    my $packet = Net::Pcap::next($pcap, $header);
    return -2 if $pcap->{closed};
    return -2 unless defined $packet;
    $$data = $packet;
    return 1;
}

sub datalink { _descriptor($_[0])->{linktype} }
sub snapshot { _descriptor($_[0])->{snaplen} }
sub is_swapped { _descriptor($_[0])->{swapped} }
sub major_version { _descriptor($_[0])->{major} }
sub minor_version { _descriptor($_[0])->{minor} }
sub file { _descriptor($_[0])->{path} }
sub geterr { _descriptor($_[0])->{error} || '' }

sub close {
    my ($pcap) = @_;
    _descriptor($pcap)->{closed} = 1;
    return;
}

sub compile {
    croak 'Usage: Net::Pcap::compile(p, fp, str, optimize, netmask)' unless @_ == 5;
    my ($pcap, $filter, $expression) = @_;
    _descriptor($pcap);
    croak 'arg2 not a scalar ref' unless ref($filter) eq 'SCALAR';
    $$filter = bless { expression => defined($expression) ? "$expression" : '' }, 'bpf_programPtr';
    return 0;
}

sub setfilter {
    my ($pcap, $filter) = @_;
    _descriptor($pcap)->{filter} = $filter;
    return 0;
}

sub freecode { return }
sub breakloop { _descriptor($_[0])->{breakloop} = 1; return }

sub loop {
    my ($pcap, $count, $callback, $user) = @_;
    my $processed = 0;
    while ($count < 0 || $processed < $count) {
        my (%header, $packet);
        last unless defined($packet = Net::Pcap::next($pcap, \%header));
        $callback->($user, \%header, $packet);
        $processed++;
        if (delete $pcap->{breakloop}) { return -2 }
    }
    return $processed;
}

sub dispatch { goto &loop }

sub open_dead {
    my ($linktype, $snaplen) = @_;
    croak 'Usage: Net::Pcap::open_dead(linktype, snaplen)' unless @_ == 2;
    return bless {
        packets => [], index => 0, linktype => $linktype, snaplen => $snaplen,
        major => 2, minor => 4, swapped => 0, error => '',
    }, 'pcap_tPtr';
}

sub lib_version { 'libpcap version 1.0 (PerlOnJava offline reader)' }
sub stats { my (undef, $stats) = @_; %$stats = (ps_recv => 0, ps_drop => 0, ps_ifdrop => 0); return 0 }
sub set_datalink { -1 }
sub setnonblock { 0 }
sub getnonblock { 0 }
sub get_selectable_fd { -1 }
sub fileno { -1 }
sub strerror { defined($_[0]) ? "$_[0]" : '' }
sub perror { return }

sub datalink_name_to_val {
    my %values = (NULL => 0, EN10MB => 1, PPP => 9, RAW => 12,
        IEEE802_11 => 105, LINUX_SLL => 113, IEEE802_11_RADIO => 127);
    return -1 unless defined $_[0];
    return exists($values{uc($_[0])}) ? $values{uc($_[0])} : -1;
}

sub datalink_val_to_name {
    my %names = (0 => 'NULL', 1 => 'EN10MB', 9 => 'PPP', 12 => 'RAW',
        105 => 'IEEE802_11', 113 => 'LINUX_SLL', 127 => 'IEEE802_11_RADIO');
    return $names{$_[0]};
}

sub datalink_val_to_description {
    my %descriptions = (0 => 'BSD loopback', 1 => 'Ethernet', 9 => 'PPP',
        12 => 'Raw IP', 105 => '802.11', 113 => 'Linux cooked',
        127 => '802.11 plus radiotap header');
    return $descriptions{$_[0]};
}

sub _descriptor {
    my ($pcap) = @_;
    croak 'p is not of type pcap_tPtr' unless ref($pcap) eq 'pcap_tPtr';
    return $pcap;
}

sub _u16 { unpack($_[2] ? 'v' : 'n', substr($_[0], $_[1], 2)) }
sub _u32 { unpack($_[2] ? 'V' : 'N', substr($_[0], $_[1], 4)) }
sub _host_is_little { unpack('C', pack('S', 1)) == 1 }

sub _unsupported {
    my ($name, @args) = @_;
    my $last = @args ? $args[-1] : undef;
    $$last = "$name is unavailable: PerlOnJava Net::Pcap supports offline captures"
        if ref($last) eq 'SCALAR';
    return;
}

{
    no strict 'refs';
    for my $name (@FUNCTIONS) {
        *{__PACKAGE__ . "::$name"} = sub { _unsupported($name, @_) }
            unless __PACKAGE__->can($name);
        *{__PACKAGE__ . "::pcap_$name"} = \&{__PACKAGE__ . "::$name"};
    }
}

1;

__END__

=head1 NAME

Net::Pcap - portable offline capture reader for PerlOnJava

=head1 DESCRIPTION

This compatibility implementation reads classic pcap and pcapng captures and
implements the descriptor operations used by C<Net::Frame::Dump::Offline>.
Live packet capture remains unavailable.

=cut
