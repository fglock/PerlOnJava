use strict;
use warnings;
use Test::More;
use IO::Socket::SSL;

my $port = __PORT__;
my $expected = 'x' x 8192;
my $body = read_chunked_response($port);

is(length($body), length($expected), 'the TLS response body is read to completion');
is($body, $expected, 'the complete chunked TLS response body is preserved');
done_testing;

sub read_chunked_response {
    my ($port) = @_;
    return read_response(
        IO::Socket::SSL->new(
            PeerHost => '127.0.0.1',
            PeerPort => $port,
            SSL_verify_mode => SSL_VERIFY_NONE,
            Timeout => 10,
        )
    );
}

sub read_response {
    print { $_[0] } "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
        or die "write HTTP request: $!";

    my $wire = '';
    my $count = sysread($_[0], $wire, 2048);
    die "read TLS response headers: $!" unless defined $count;

    my $header_end = index($wire, "\r\n\r\n");
    die 'response headers are incomplete' if $header_end < 0;
    my $headers = substr($wire, 0, $header_end);
    substr($wire, 0, $header_end + 4, '');
    die 'response is not chunked' unless $headers =~ /^Transfer-Encoding:\s*chunked\s*$/mi;
    ok(length($wire) > 0, 'the TLS header read also prefetched chunk-body bytes');

    # The active argument must retain the socket after this captured lexical
    # leaves scope. The first TLS read above also leaves body bytes buffered.
    {
        my $captured_socket = $_[0];
        my $callback = sub { $captured_socket };
    }

    my $body = '';
    while (1) {
        my $line_end = index($wire, "\r\n");
        while ($line_end < 0) {
            my $chunk = '';
            my $read = sysread($_[0], $chunk, 4096);
            die "read TLS chunk header: $!" unless defined $read;
            die 'unexpected TLS EOF in chunk header' unless $read;
            $wire .= $chunk;
            $line_end = index($wire, "\r\n");
        }

        my $line = substr($wire, 0, $line_end, '');
        substr($wire, 0, 2, '');
        $line =~ s/;.*\z//;
        die "invalid chunk size: $line" unless $line =~ /\A[0-9a-fA-F]+\z/;
        my $size = hex($line);
        last unless $size;

        while (length($wire) < $size + 2) {
            my $chunk = '';
            my $read = sysread($_[0], $chunk, 4096);
            die "read TLS chunk body: $!" unless defined $read;
            die 'unexpected TLS EOF in chunk body' unless $read;
            $wire .= $chunk;
        }

        $body .= substr($wire, 0, $size, '');
        die 'missing chunk terminator' unless substr($wire, 0, 2, '') eq "\r\n";
    }

    return $body;
}
