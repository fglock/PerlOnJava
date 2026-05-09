# -*- perl -*-
# Synced from perl 5.34.0 lib/Net/hostent.pm — core-style Net::

package Net::hostent;
use strict;

use 5.006_001;
our $VERSION = '1.02';
our (@EXPORT, @EXPORT_OK, %EXPORT_TAGS);
our (
 $h_name, @h_aliases,
 $h_addrtype, $h_length,
 @h_addr_list, $h_addr
);

BEGIN {
 use Exporter ();
 @EXPORT = qw(gethostbyname gethostbyaddr gethost);
 @EXPORT_OK = qw(
 $h_name @h_aliases
 $h_addrtype $h_length
 @h_addr_list $h_addr
 );
 %EXPORT_TAGS = ( FIELDS => [ @EXPORT_OK, @EXPORT ] );
}

# Class::Struct forbids use of @ISA
sub import { goto &Exporter::import }

use Class::Struct qw(struct);
struct 'Net::hostent' => [
 name => '$',
 aliases => '@',
 addrtype => '$',
 'length' => '$',
 addr_list => '@',
];

sub addr { shift->addr_list->[0] }

sub populate (@) {
 return unless @_;
 my $hob = new();
 $h_name = $hob->[0] = $_[0];
 @h_aliases = @{ $hob->[1] } = split ' ', $_[1];
 $h_addrtype = $hob->[2] = $_[2];
 $h_length = $hob->[3] = $_[3];
 $h_addr = $_[4];
 @h_addr_list = @{ $hob->[4] } = @_[ (4 .. $#_) ];
 return $hob;
}

sub gethostbyname ($) { populate(CORE::gethostbyname(shift)) }

sub gethostbyaddr ($;$) {
 my ($addr, $addrtype);
 $addr = shift;
 require Socket unless @_;
 $addrtype = @_ ? shift : Socket::AF_INET();
 populate(CORE::gethostbyaddr($addr, $addrtype))
}

sub gethost($) {
 if ($_[0] =~ /^\d+(?:\.\d+(?:\.\d+(?:\.\d+)?)?)?$/) {
 require Socket;
 &gethostbyaddr(Socket::inet_aton(shift));
 } else {
 &gethostbyname;
 }
}

1;

__END__

=head1 NAME

Net::hostent - by-name interface to Perl's built-in gethost*() functions

=cut
