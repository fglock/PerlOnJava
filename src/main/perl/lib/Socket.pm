package Socket;

use Exporter "import";
use warnings;
use strict;

XSLoader::load('Socket');

# NOTE: The rest of the implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/Socket.java

# Define constants as subroutines for proper bareword usage
use constant {
    AF_INET      => 2,
    PF_INET      => 2,
    SOCK_STREAM  => 1,
    SOCK_DGRAM   => 2,
    SOL_SOCKET   => 1,
    SO_REUSEADDR => 2,
};

our @EXPORT = qw(
    pack_sockaddr_in unpack_sockaddr_in
    inet_aton inet_ntoa
    sockaddr_in
    AF_INET PF_INET SOCK_STREAM SOCK_DGRAM
    SOL_SOCKET SO_REUSEADDR
);

our @EXPORT_OK = qw(
    pack_sockaddr_in unpack_sockaddr_in
    inet_aton inet_ntoa
    sockaddr_in
    AF_INET PF_INET SOCK_STREAM SOCK_DGRAM
    SOL_SOCKET SO_REUSEADDR
);

our %EXPORT_TAGS = (
    DEFAULT => [qw(pack_sockaddr_in unpack_sockaddr_in inet_aton inet_ntoa)],
    crlf => [qw(CR LF CRLF)],
);

# Constants are provided by the Java implementation
# AF_INET = 2, SOCK_STREAM = 1, SOCK_DGRAM = 2, etc.

1;

__END__

=head1 NAME

Socket - Basic socket constants and functions for PerlOnJava

=head1 SYNOPSIS

    use Socket qw(AF_INET SOCK_STREAM inet_aton sockaddr_in);
    
    socket(my $sock, AF_INET, SOCK_STREAM, 0);
    my $addr = sockaddr_in(80, inet_aton('127.0.0.1'));
    bind($sock, $addr);

=head1 DESCRIPTION

This is a basic Socket module implementation for PerlOnJava that provides
essential socket constants and basic address manipulation functions.

=head1 CONSTANTS

=over 4

=item AF_INET, PF_INET

IPv4 address family (value: 2)

=item AF_INET6, PF_INET6  

IPv6 address family (value: 10)

=item AF_UNIX, PF_UNIX

Unix domain socket family (value: 1)

=item SOCK_STREAM

Stream socket type for TCP (value: 1)

=item SOCK_DGRAM

Datagram socket type for UDP (value: 2)

=back

=head1 FUNCTIONS

=over 4

=item inet_aton($ip_address)

Converts a dotted-decimal IP address string to a packed binary format.

=item sockaddr_in($port, $addr)

Creates a packed socket address structure for IPv4.

=back

=head1 NOTE

This is a simplified implementation for PerlOnJava. It provides basic
functionality to support socket operations but may not include all
features of the standard Perl Socket module.

=cut
