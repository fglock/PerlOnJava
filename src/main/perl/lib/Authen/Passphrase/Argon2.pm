package Authen::Passphrase::Argon2;
use 5.014;
use strict;
use warnings;
use Carp qw(croak);
use parent 'Authen::Passphrase';
use Crypt::Argon2 qw(argon2id_pass argon2id_verify);

our $VERSION = '1.01';

sub new {
    my ($class, @input) = @_;
    @input = %{$input[0]} if @input == 1 && ref($input[0]) eq 'HASH';
    my %args = @input;
    my $salt = exists $args{salt} ? $args{salt}
        : exists $args{salt_random} ? Crypt::Argon2::_argon2_random_bytes(16)
        : croak 'salt not set';
    my $self = bless {
        algorithm => 'Argon2', salt => $salt, cost => ($args{cost} || 3),
        factor => ($args{factor} || '32M'), parallelism => ($args{parallelism} || 1),
        size => ($args{size} || 16),
    }, $class;
    my $value = $args{passphrase};
    $value = $args{hash} if exists $args{hash};
    croak 'hash or passphrase not set' unless defined $value;
    $self->{crypt} = $value =~ /^\$argon2/ ? $value : $self->_hash_of($value);
    return $self;
}

sub _hash_of { argon2id_pass($_[1], $_[0]->{salt}, $_[0]->{cost}, $_[0]->{factor}, $_[0]->{parallelism}, $_[0]->{size}) }
sub match { argon2id_verify($_[0]->{crypt}, $_[1]) }
sub from_crypt { my ($class, $value) = @_; $value =~ /^\$argon2id/ or croak 'not a valid raw hash'; return bless {crypt => $value, algorithm => 'Argon2'}, $class }
sub from_rfc2307 { my ($class, $value) = @_; $value =~ /^\{ARGON2\}(.*)$/ or croak 'invalid Argon2 RFC2307 format'; return $class->from_crypt($1) }
sub as_crypt { $_[0]->{crypt} = $_[1] if defined $_[1]; $_[0]->{crypt} }
sub as_rfc2307 { '{ARGON2}' . $_[0]->as_crypt }
sub algorithm { $_[0]->{algorithm} }
sub salt { $_[0]->{salt} }
sub hash { $_[0]->{crypt} }

1;
