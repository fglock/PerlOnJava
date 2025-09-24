package Socket;

use strict;
use warnings;

# Socket domain constants
use constant AF_INET => 2;
use constant AF_INET6 => 10;
use constant AF_UNIX => 1;
use constant PF_INET => 2;
use constant PF_INET6 => 10;
use constant PF_UNIX => 1;

# Socket type constants
use constant SOCK_STREAM => 1;
use constant SOCK_DGRAM => 2;

# Export the constants
require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(
    AF_INET AF_INET6 AF_UNIX
    PF_INET PF_INET6 PF_UNIX
    SOCK_STREAM SOCK_DGRAM
    inet_aton sockaddr_in
);

# Basic inet_aton implementation - converts IP address to packed format
sub inet_aton {
    my $ip = shift;
    return pack("C4", split /\./, $ip);
}

# Basic sockaddr_in implementation - creates packed socket address
sub sockaddr_in {
    my ($port, $addr) = @_;
    return pack("n N", $port, unpack("N", $addr));
}

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
