package IO::Socket::SSL;
use strict;
use warnings;

our $VERSION = '2.089';

use XSLoader;
XSLoader::load('IO::Socket::SSL', $VERSION);

use base qw(IO::Socket::IP);

use Carp qw(croak);

# SSL verification modes (match OpenSSL constants)
use constant SSL_VERIFY_NONE                 => 0x00;
use constant SSL_VERIFY_PEER                 => 0x01;
use constant SSL_VERIFY_FAIL_IF_NO_PEER_CERT => 0x02;
use constant SSL_VERIFY_CLIENT_ONCE          => 0x04;

# SSL non-blocking want-* state codes (match OpenSSL SSL_ERROR_WANT_* values).
# Used by Mojo::IOLoop::TLS and other clients that drive non-blocking handshakes.
# We don't actually surface these from this stub yet (configure() always blocks),
# but they need to be defined as constants so callers using
# `IO::Socket::SSL::SSL_WANT_READ()` at compile time don't fail.
use constant SSL_WANT_READ         => 2;
use constant SSL_WANT_WRITE        => 3;
use constant SSL_WANT_X509_LOOKUP  => 4;
use constant SSL_WANT_CONNECT      => 7;
use constant SSL_WANT_ACCEPT       => 8;

our $SSL_ERROR = '';

our @EXPORT_OK = qw(
    SSL_VERIFY_NONE SSL_VERIFY_PEER
    SSL_VERIFY_FAIL_IF_NO_PEER_CERT SSL_VERIFY_CLIENT_ONCE
    SSL_WANT_READ SSL_WANT_WRITE SSL_WANT_X509_LOOKUP
    SSL_WANT_CONNECT SSL_WANT_ACCEPT
);

our %EXPORT_TAGS = (
    ssl => [qw(SSL_VERIFY_NONE SSL_VERIFY_PEER
               SSL_VERIFY_FAIL_IF_NO_PEER_CERT SSL_VERIFY_CLIENT_ONCE
               SSL_WANT_READ SSL_WANT_WRITE SSL_WANT_X509_LOOKUP
               SSL_WANT_CONNECT SSL_WANT_ACCEPT)],
);

# Error string accessor (class method)
sub errstr { return $SSL_ERROR }

# Provide default_ca — if we return a truthy value, LWP won't require Mozilla::CA
# Java uses its own cacerts trust store which includes standard CAs
sub default_ca {
    return 1;
}

# configure() is the main entry point, called by:
#   1. IO::Socket::SSL->new(%args) → IO::Socket->new → $self->configure(\%args)
#   2. Net::HTTPS::http_connect → $self->SUPER::configure($cnf)
# We intercept SSL_* args, do the TCP connect via SUPER, then upgrade to SSL.
sub configure {
    my ($self, $cnf) = @_;

    # Extract SSL-specific options
    my %ssl_opts;
    for my $key (keys %$cnf) {
        if ($key =~ /^SSL_/) {
            $ssl_opts{$key} = delete $cnf->{$key};
        }
    }

    # Remove options that IO::Socket::IP doesn't understand
    delete $cnf->{MultiHomed};

    # Work around PerlOnJava issue: IO::Socket::IP non-blocking connect
    # with Timeout causes "Input/output error". IO::Socket::new() already
    # extracted Timeout from args and stored it in ${*$self}{'io_socket_timeout'}.
    # Clear it so IO::Socket::IP::connect() does a simple blocking connect.
    my $timeout = delete ${*$self}{'io_socket_timeout'};
    delete $cnf->{Timeout};  # in case it's still there

    # Store SSL options on the glob
    ${*$self}{_ssl_opts} = \%ssl_opts;

    # Save the original PeerAddr/PeerHost for SNI hostname resolution
    # (peerhost() returns the IP address, not the hostname)
    ${*$self}{_ssl_peer_host} = $cnf->{PeerAddr} // $cnf->{PeerHost} // '';

    # Do TCP connect via IO::Socket::IP
    $self->SUPER::configure($cnf) or return;

    # Upgrade to SSL
    unless ($self->connect_SSL) {
        close($self);
        return;
    }

    return $self;
}

# Perform the actual SSL handshake on an already-connected socket
sub connect_SSL {
    my ($self) = @_;

    my $ssl_opts = ${*$self}{_ssl_opts} || {};

    # Determine hostname for SNI
    my $host = $ssl_opts->{SSL_hostname}
            // $ssl_opts->{SSL_verifycn_name}
            // '';

    # Fall back to the original PeerAddr saved during configure
    if ($host eq '' || $host =~ /^[\d.]+$/ || $host =~ /:/) {
        my $saved = ${*$self}{_ssl_peer_host} // '';
        $host = $saved if $saved ne '' && $saved !~ /^[\d.]+$/ && $saved !~ /:/;
    }

    # Last resort: try peerhost (returns IP address)
    if ($host eq '') {
        $host = eval { $self->peerhost } || '';
    }

    my $port = eval { $self->peerport } || 443;

    # Determine verify mode
    my $verify_mode = $ssl_opts->{SSL_verify_mode};
    $verify_mode = SSL_VERIFY_PEER unless defined $verify_mode;

    # Check verify scheme — 'none' means no verification
    if (defined $ssl_opts->{SSL_verifycn_scheme}
        && $ssl_opts->{SSL_verifycn_scheme} eq 'none') {
        $verify_mode = SSL_VERIFY_NONE;
    }

    my $ca_file = $ssl_opts->{SSL_ca_file};
    my $ca_path = $ssl_opts->{SSL_ca_path};

    # Call into Java XS to perform the SSL upgrade
    my $ok = IO::Socket::SSL::_start_ssl(
        $self, $host, $port, $verify_mode, $ca_file, $ca_path
    );

    unless ($ok) {
        $@ = $SSL_ERROR;
        return;
    }

    # Mark as SSL
    ${*$self}{_is_ssl} = 1;

    return 1;
}

# Class method: upgrade an existing connected socket to SSL
# Used by LWP::Protocol::https for CONNECT proxy tunneling
sub start_SSL {
    my ($class, $sock, %args) = @_;

    # Rebless the socket into our class if it isn't already
    if (!$sock->isa('IO::Socket::SSL')) {
        bless $sock, ref($class) || $class;
    }

    # Store SSL options
    my %ssl_opts;
    for my $key (keys %args) {
        if ($key =~ /^SSL_/) {
            $ssl_opts{$key} = $args{$key};
        }
    }
    ${*$sock}{_ssl_opts} = \%ssl_opts;

    # Perform SSL handshake
    unless ($sock->connect_SSL) {
        $SSL_ERROR = $@ || 'SSL handshake failed';
        return;
    }

    return $sock;
}

# Get the negotiated cipher suite
sub get_cipher {
    my ($self) = @_;
    return IO::Socket::SSL::_get_cipher($self) || '';
}

# Get TLS protocol version
sub get_sslversion {
    my ($self) = @_;
    return IO::Socket::SSL::_get_sslversion($self) || '';
}

# Get peer certificate — returns a small object with subject_name/issuer_name
sub get_peer_certificate {
    my ($self) = @_;

    my $has_cert = IO::Socket::SSL::_peer_certificate($self);
    return unless $has_cert;

    return IO::Socket::SSL::_PeerCert->new($self);
}

# peer_certificate with field selection (IO::Socket::SSL API)
sub peer_certificate {
    my ($self, $field) = @_;
    if (defined $field) {
        return IO::Socket::SSL::_peer_certificate($self, $field);
    }
    return $self->get_peer_certificate;
}

# Check if this socket is SSL-wrapped
sub is_SSL {
    my ($self) = @_;
    return IO::Socket::SSL::_is_ssl($self) ? 1 : 0;
}

# Return SSL data still in buffer (needed by Net::HTTP::Methods)
sub pending {
    my ($self) = @_;
    return 0;
}

# Stop SSL on this connection (not truly supported with SSLSocket wrapping)
sub stop_SSL {
    my ($self) = @_;
    return IO::Socket::SSL::_stop_ssl($self);
}

# --- Certificate inspection helper class ---
package IO::Socket::SSL::_PeerCert;

sub new {
    my ($class, $sock) = @_;
    return bless { _sock => $sock }, $class;
}

sub subject_name {
    my ($self) = @_;
    return IO::Socket::SSL::_peer_certificate_subject($self->{_sock});
}

sub issuer_name {
    my ($self) = @_;
    return IO::Socket::SSL::_peer_certificate_issuer($self->{_sock});
}

1;

__END__

=head1 NAME

IO::Socket::SSL - PerlOnJava SSL/TLS socket implementation using javax.net.ssl

=head1 DESCRIPTION

This is a simplified IO::Socket::SSL implementation for PerlOnJava that uses
Java's javax.net.ssl (SSLSocket, SSLContext) instead of OpenSSL/Net::SSLeay.

It provides the subset of the IO::Socket::SSL API needed by LWP::Protocol::https
and Net::HTTPS.

=head1 SUPPORTED FEATURES

=over 4

=item * SSL_verify_mode (VERIFY_NONE and VERIFY_PEER)

=item * SSL_ca_file and SSL_ca_path for custom CA certificates

=item * SNI (Server Name Indication)

=item * TLSv1.2 and TLSv1.3

=item * Certificate inspection (subject, issuer, dates)

=item * start_SSL() for upgrading existing connections

=back

=cut
