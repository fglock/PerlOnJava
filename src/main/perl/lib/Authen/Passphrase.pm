package Authen::Passphrase;
use strict;
use warnings;
use Carp qw(croak);

our $VERSION = '0.009';

sub from_crypt {
    my ($class, $value) = @_;
    croak "from_crypt called on subclass $class" unless $class eq __PACKAGE__;
    if ($value =~ /^\$2a?\$/) {
        require Authen::Passphrase::BlowfishCrypt;
        return Authen::Passphrase::BlowfishCrypt->from_crypt($value);
    }
    croak "unrecognised crypt scheme in $value";
}

sub from_rfc2307 {
    my ($class, $value) = @_;
    if ($value =~ /^\{(?:CRYPT|WM-CRY)\}(.*)$/i) {
        return $class->from_crypt($1);
    }
    croak "RFC 2307 string not supported for $class" unless $class eq __PACKAGE__;
    if ($value =~ /^\{CLEARTEXT\}/i) {
        require Authen::Passphrase::Clear;
        return Authen::Passphrase::Clear->from_rfc2307($value);
    }
    croak "unrecognised RFC 2307 scheme";
}

sub as_rfc2307 { '{CRYPT}' . $_[0]->as_crypt }
sub match { croak 'abstract match method' }
sub passphrase { croak 'passphrase cannot be recovered' }
sub as_crypt { croak 'passphrase cannot be expressed as a crypt string' }

1;
