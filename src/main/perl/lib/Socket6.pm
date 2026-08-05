# Copyright (C) 2000-2018 Hajimu UMEMOTO <ume@mahoroba.org>.
# Copyright (C) 1995-1999 WIDE Project.
#
# PerlOnJava compatibility implementation.  Socket6's XS entry points are
# adapters around the IPv6 support already provided by the bundled Socket.

package Socket6;

use strict;
use warnings;
use Exporter 'import';
use Socket ();

our $VERSION = '0.29';

our @EXPORT = qw(
    inet_pton inet_ntop pack_sockaddr_in6 pack_sockaddr_in6_all
    unpack_sockaddr_in6 unpack_sockaddr_in6_all sockaddr_in6
    gethostbyname2 getipnodebyname
    getaddrinfo getnameinfo in6addr_any in6addr_loopback
    AF_INET6 PF_INET6 AI_PASSIVE AI_CANONNAME AI_NUMERICHOST AI_ADDRCONFIG
    NI_NUMERICHOST NI_NUMERICSERV NI_NUMERICSERVER NI_DGRAM EAI_NONAME EAI_FAIL
);
our @EXPORT_OK = @EXPORT;
our %EXPORT_TAGS = (all => \@EXPORT);

sub AF_INET6      () { Socket::AF_INET6() }
sub PF_INET6      () { Socket::PF_INET6() }
sub AI_PASSIVE    () { Socket::AI_PASSIVE() }
sub AI_CANONNAME  () { Socket::AI_CANONNAME() }
sub AI_NUMERICHOST() { Socket::AI_NUMERICHOST() }
sub AI_ADDRCONFIG () { Socket::AI_ADDRCONFIG() }
sub NI_NUMERICHOST() { Socket::NI_NUMERICHOST() }
sub NI_NUMERICSERV() { Socket::NI_NUMERICSERV() }
sub NI_NUMERICSERVER() { Socket::NI_NUMERICSERV() }
sub NI_DGRAM      () { Socket::NI_DGRAM() }
sub EAI_NONAME    () { Socket::EAI_NONAME() }
sub EAI_FAIL      () { Socket::EAI_FAIL() }

sub inet_pton { Socket::inet_pton(@_) }
sub inet_ntop { Socket::inet_ntop(@_) }
sub pack_sockaddr_in6 { Socket::pack_sockaddr_in6(@_) }
sub unpack_sockaddr_in6 {
    my ($port, $addr) = Socket::unpack_sockaddr_in6(@_);
    return wantarray ? ($port, $addr) : $port;
}
sub pack_sockaddr_in6_all {
    my ($port, $flowinfo, $addr, $scope_id) = @_;
    return Socket::pack_sockaddr_in6($port, $addr, $scope_id, $flowinfo);
}
sub unpack_sockaddr_in6_all {
    my ($port, $addr, $scope_id, $flowinfo) =
        Socket::unpack_sockaddr_in6(@_);
    return ($port, $flowinfo, $addr, $scope_id);
}
sub sockaddr_in6 {
    return @_ == 1 ? unpack_sockaddr_in6(@_) : pack_sockaddr_in6(@_);
}

# Compatibility entry points used by older pure-Perl IPv6 consumers.  The
# bundled Socket6 implementation has no XS resolver, so use Perl's resolver
# for the IPv4-compatible path and return the traditional host record shape.
sub gethostbyname2 {
    my ($host, $family) = @_;
    return gethostbyname($host);
}

sub getipnodebyname {
    my ($host, $family, $flags) = @_;
    return gethostbyname($host);
}
sub in6addr_any      { Socket::IN6ADDR_ANY() }
sub in6addr_loopback { Socket::IN6ADDR_LOOPBACK() }

# Socket6 predates core Socket's hash-based getaddrinfo API.  Preserve its
# flat five-value records while delegating resolution to the Java runtime.
sub getaddrinfo {
    my ($node, $service, $family, $socktype, $protocol, $flags) = @_;
    my ($error, @records) = Socket::getaddrinfo(
        $node,
        $service,
        {
            family   => $family   || 0,
            socktype => $socktype || 0,
            protocol => $protocol || 0,
            flags    => $flags    || 0,
        },
    );
    return $error if $error;
    my @result;
    for my $record (@records) {
        push @result, @{$record}{qw(family socktype protocol addr canonname)};
    }
    return @result;
}

sub getnameinfo {
    my ($error, $host, $service) = Socket::getnameinfo(@_);
    return $error ? $error : ($host, $service);
}

1;
