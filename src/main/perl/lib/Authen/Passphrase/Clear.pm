package Authen::Passphrase::Clear;
use strict;
use warnings;
use Carp qw(croak);
use parent 'Authen::Passphrase';

our $VERSION = '0.009';

sub new {
    my ($class, $passphrase) = @_;
    $passphrase = "$passphrase";
    return bless \$passphrase, $class;
}

sub from_rfc2307 {
    my ($class, $value) = @_;
    $value =~ /^\{CLEARTEXT\}([!-~]*)$/i or croak 'malformed {CLEARTEXT} data';
    return $class->new($1);
}

sub match { $_[1] eq ${$_[0]} }
sub passphrase { ${$_[0]} }
sub as_rfc2307 {
    croak "can't put this passphrase into an RFC 2307 string" if ${$_[0]} =~ /[^!-~]/;
    return '{CLEARTEXT}' . ${$_[0]};
}

1;
