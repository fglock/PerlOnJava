package Socket;

#
# Original Socket module maintained in Perl core by the Perl 5 Porters.
# It was extracted to dual-life on CPAN at version 1.95 by
# Paul Evans <leonerd@leonerd.org.uk>
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/Socket.java
#

use Exporter "import";
use warnings;
use strict;

our $VERSION = '2.038';

XSLoader::load('Socket');

# NOTE: The rest of the implementation is in file:
#       src/main/java/org/perlonjava/perlmodule/Socket.java

our @EXPORT = qw(
    pack_sockaddr_in unpack_sockaddr_in
    pack_sockaddr_un unpack_sockaddr_un
    inet_aton inet_ntoa getnameinfo getaddrinfo
    sockaddr_in sockaddr_un sockaddr_family
    AF_INET AF_INET6 AF_UNIX
    PF_INET PF_INET6 PF_UNIX PF_UNSPEC
    SOCK_STREAM SOCK_DGRAM SOCK_RAW
    SOL_SOCKET SO_REUSEADDR SO_KEEPALIVE SO_BROADCAST SO_LINGER SO_ERROR SO_TYPE SO_REUSEPORT
    SOMAXCONN
    INADDR_ANY INADDR_LOOPBACK INADDR_BROADCAST
    IPPROTO_TCP IPPROTO_UDP IPPROTO_ICMP IPPROTO_IP IPPROTO_IPV6
    IP_TOS IP_TTL IPV6_V6ONLY
    TCP_NODELAY
    SHUT_RD SHUT_WR SHUT_RDWR
    AI_PASSIVE AI_CANONNAME AI_NUMERICHOST AI_ADDRCONFIG
    NI_NUMERICHOST NI_NUMERICSERV NI_DGRAM
    NIx_NOHOST NIx_NOSERV
    EAI_NONAME
);

our @EXPORT_OK = @EXPORT;

our %EXPORT_TAGS = (
    all => \@EXPORT,
    DEFAULT => [qw(pack_sockaddr_in unpack_sockaddr_in inet_aton inet_ntoa)],
    crlf => [qw(CR LF CRLF)],
);

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

=head1 AUTHOR

Original module maintained in Perl core by the Perl 5 Porters.
It was extracted to dual-life on CPAN at version 1.95 by
Paul Evans <leonerd@leonerd.org.uk>

PerlOnJava implementation by Flavio S. Glock.

=cut
