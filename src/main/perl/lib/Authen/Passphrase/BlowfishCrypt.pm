package Authen::Passphrase::BlowfishCrypt;
use strict;
use warnings;
use Carp qw(croak);
use parent 'Authen::Passphrase';
use Crypt::Eksblowfish::Bcrypt 0.008 qw(bcrypt_hash en_base64 de_base64);

our $VERSION = '0.009';

sub new {
    my $class = shift;
    my ($passphrase, %self);
    while (@_) {
        my ($key, $value) = (shift, shift);
        if ($key eq 'key_nul') { croak 'key_nul specified redundantly' if exists $self{key_nul}; $self{key_nul} = !!$value }
        elsif ($key eq 'cost' || $key eq 'keying_nrounds_log2') { croak 'cost specified redundantly' if exists $self{cost}; $self{cost} = 0 + $value }
        elsif ($key eq 'salt') { croak 'salt specified redundantly' if exists $self{salt}; $self{salt} = "$value" }
        elsif ($key eq 'salt_base64') { croak 'salt specified redundantly' if exists $self{salt}; $self{salt} = de_base64($value) }
        elsif ($key eq 'salt_random') { croak 'salt specified redundantly' if exists $self{salt}; $self{salt} = Crypt::Eksblowfish::Bcrypt::_bcrypt_random_bytes(16) }
        elsif ($key eq 'hash') { croak 'hash specified redundantly' if exists $self{hash} || defined $passphrase; $self{hash} = "$value" }
        elsif ($key eq 'hash_base64') { croak 'hash specified redundantly' if exists $self{hash} || defined $passphrase; $self{hash} = de_base64($value) }
        elsif ($key eq 'passphrase') { croak 'passphrase specified redundantly' if exists $self{hash} || defined $passphrase; $passphrase = $value }
        else { croak "unrecognised attribute `$key'" }
    }
    $self{key_nul} = 1 unless exists $self{key_nul};
    croak 'cost not specified' unless exists $self{cost};
    croak 'salt not specified' unless length($self{salt}) == 16;
    my $self = bless \%self, $class;
    $self->{hash} = $self->_hash_of($passphrase) if defined $passphrase;
    croak 'hash not specified' unless length($self->{hash}) == 23;
    return $self;
}

sub from_crypt {
    my ($class, $value) = @_;
    $value =~ /^\$2(a?)\$([0-9]{2})\$([.\/A-Za-z0-9]{22})([.\/A-Za-z0-9]{31})$/
        or return $class->SUPER::from_crypt($value);
    return $class->new(key_nul => !!$1, cost => $2,
        salt_base64 => $3, hash_base64 => $4);
}

sub from_rfc2307 {
    my ($class, $value) = @_;
    $value =~ /^\{CRYPT\}(.*)$/i or return $class->SUPER::from_rfc2307($value);
    return $class->from_crypt($1);
}

sub key_nul { $_[0]->{key_nul} }
sub cost { $_[0]->{cost} }
*keying_nrounds_log2 = \&cost;
sub salt { $_[0]->{salt} }
sub salt_base64 { en_base64($_[0]->{salt}) }
sub hash { $_[0]->{hash} }
sub hash_base64 { en_base64($_[0]->{hash}) }
sub _hash_of { bcrypt_hash({key_nul => $_[0]->{key_nul}, cost => $_[0]->{cost}, salt => $_[0]->{salt}}, $_[1]) }
sub match { $_[0]->_hash_of($_[1]) eq $_[0]->{hash} }
sub as_crypt { sprintf("\$2%s\$%02d\$%s%s", $_[0]->key_nul ? 'a' : '', $_[0]->cost, $_[0]->salt_base64, $_[0]->hash_base64) }

1;
