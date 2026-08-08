package Authen::Passphrase::Scrypt;
use 5.014;
use strict;
use warnings;
use Carp qw(croak);
use parent qw(Exporter Authen::Passphrase);
use Digest::SHA qw(sha256 hmac_sha256);
use MIME::Base64 qw(encode_base64 decode_base64);

our $VERSION = '0.002';
our @EXPORT = qw(crypto_scrypt);
our @EXPORT_OK = @EXPORT;

use XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

sub compute_hash { crypto_scrypt($_[1], $_[0]->{salt}, 1 << $_[0]->{logN}, $_[0]->{r}, $_[0]->{p}, 64) }
sub truncated_sha256 { substr sha256($_[0]), 0, 16 }
sub truncate_hash { substr $_[0], 32 }

sub new {
    my ($class, @args) = @_;
    @args = %{$args[0]} if @args == 1 && ref($args[0]) eq 'HASH';
    my %args = (logN => 14, r => 16, p => 1, @args);
    $args{salt} = _scrypt_random_bytes(32) unless exists $args{salt};
    croak 'passphrase not set' unless defined $args{passphrase};
    my $self = bless \%args, $class;
    my $data = "scrypt\0" . pack('CNNa32', @args{qw(logN r p salt)});
    $data .= truncated_sha256($data);
    $self->{data} = $data;
    $self->{hmac} = hmac_sha256($data, truncate_hash($self->compute_hash($self->{passphrase})));
    return $self;
}

sub from_rfc2307 {
    my ($class, $value) = @_;
    $value =~ /^\{SCRYPT\}([A-Za-z0-9+\/]{128})$/ or croak 'Invalid Scrypt RFC2307';
    my $data = decode_base64($1);
    my ($name, $logN, $r, $p, $salt, $checksum, $hmac) = unpack('Z7CNNa32a16a32', $data);
    croak 'Invalid Scrypt hash: should start with "scrypt"' unless $name eq 'scrypt';
    croak 'Invalid Scrypt hash: bad checksum' unless $checksum eq truncated_sha256(substr($data, 0, 48));
    return bless {data => substr($data, 0, 64), logN => $logN, r => $r, p => $p, salt => $salt, hmac => $hmac}, $class;
}

sub match { $_[0]->{hmac} eq hmac_sha256($_[0]->{data}, truncate_hash($_[0]->compute_hash($_[1]))) }
sub as_rfc2307 { '{SCRYPT}' . encode_base64($_[0]->{data} . $_[0]->{hmac}, '') }
sub from_crypt { croak __PACKAGE__ . ' does not support crypt strings, use from_rfc2307 instead' }
sub as_crypt { croak __PACKAGE__ . ' does not support crypt strings, use as_rfc2307 instead' }
sub data { $_[0]->{data} }
sub logN { $_[0]->{logN} }
sub r { $_[0]->{r} }
sub p { $_[0]->{p} }
sub salt { $_[0]->{salt} }
sub hmac { $_[0]->{hmac} }
sub passphrase { $_[0]->{passphrase} }

1;
