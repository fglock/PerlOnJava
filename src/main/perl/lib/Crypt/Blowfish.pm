package Crypt::Blowfish;

require Exporter;
use strict;
use warnings;
use Carp;

our $VERSION = '2.14';
our @ISA = qw(Exporter);
our @EXPORT = ();
our @EXPORT_OK = qw(blocksize keysize min_keysize max_keysize new encrypt decrypt);

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

sub usage {
    my (undef, undef, undef, $subroutine) = caller(1);
    local $Carp::CarpLevel = 2;
    croak "Usage: $subroutine(@_)";
}
sub blocksize   { 8 }
sub keysize     { 0 }
sub min_keysize { 8 }
sub max_keysize { 56 }

sub new {
    usage('new Blowfish key') unless @_ == 2;
    my ($type, $key) = @_;
    return bless { ks => init($key) }, $type;
}

sub encrypt {
    usage('encrypt data[8 bytes]') unless @_ == 2;
    my ($self, $data) = @_;
    Crypt::Blowfish::crypt($data, $data, $self->{ks}, 0);
    return $data;
}

sub decrypt {
    usage('decrypt data[8 bytes]') unless @_ == 2;
    my ($self, $data) = @_;
    Crypt::Blowfish::crypt($data, $data, $self->{ks}, 1);
    return $data;
}

1;

__END__

=head1 NAME

Crypt::Blowfish - Blowfish encryption using PerlOnJava's BouncyCastle runtime

=head1 COPYRIGHT

Parts Copyright (C) 1995, 1996 Systemics Ltd.
New parts Copyright (C) 1999-2010 W3Works, LLC.

The original implementation and interface are distributed under the same terms
as Perl itself.  PerlOnJava replaces only the native XS block operation.

=cut
