package Crypt::PRNG;

use strict;
use warnings;
use Carp ();
use Exporter ();
use MIME::Base64 qw(encode_base64 encode_base64url);
use Crypt::OpenSSL::Random ();

our @ISA = qw(Exporter);
our %EXPORT_TAGS = (
    all => [qw(
        random_bytes random_bytes_hex random_bytes_b64 random_bytes_b64u
        random_string random_string_from rand irand
    )],
);
our @EXPORT_OK = @{ $EXPORT_TAGS{all} };
our @EXPORT = ();
our $VERSION = '0.089';

my %VALID_ALG = map { $_ => 1 } qw(ChaCha20 Fortuna RC4 Sober128 Yarrow);

sub _croak {
    Carp::croak("FATAL: $_[0]");
}

sub _check_len {
    my ($len) = @_;
    _croak('output_len too large') unless defined $len && $len >= 0 && $len <= 1_000_000_000;
    return int($len);
}

sub new {
    my ($class, $alg, $seed) = @_;
    $class = ref($class) || $class;
    $alg = 'ChaCha20' unless defined $alg;
    $alg = "$alg";
    _croak("invalid PRNG name '$alg'") unless $VALID_ALG{$alg};
    if (defined $seed) {
        _croak('PRNG_add_entropy failed: empty seed') if $seed eq '';
        _croak('PRNG_ready failed: RC4 seed too short') if $alg eq 'RC4' && length($seed) < 5;
        Crypt::OpenSSL::Random::random_seed($seed);
    }
    return bless { alg => $alg }, $class;
}

sub add_entropy {
    my ($self, $seed) = @_;
    $seed = Crypt::OpenSSL::Random::random_bytes(40) unless defined $seed;
    Crypt::OpenSSL::Random::random_seed($seed);
    return $self;
}

sub bytes {
    my ($self, $len) = @_;
    return Crypt::OpenSSL::Random::random_bytes(_check_len($len));
}

sub bytes_hex {
    my ($self, $len) = @_;
    return unpack('H*', $self->bytes($len));
}

sub bytes_b64 {
    my ($self, $len) = @_;
    return encode_base64($self->bytes($len), '');
}

sub bytes_b64u {
    my ($self, $len) = @_;
    return encode_base64url($self->bytes($len));
}

sub int32 {
    my ($self) = @_;
    my @b = unpack('C4', $self->bytes(4));
    return (($b[0] * 256 + $b[1]) * 256 + $b[2]) * 256 + $b[3];
}

sub double {
    my ($self, $limit) = @_;
    my $n = $self->int32 / 4294967296;
    return $n unless defined $limit && $limit != 0;
    return $n * $limit;
}

sub string {
    my ($self, $len) = @_;
    return $self->string_from('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789', $len);
}

sub string_from {
    my ($self, $chars, $len) = @_;
    $len = 20 unless defined $len;
    return unless $len > 0;
    return unless defined $chars && length($chars) > 0;

    my @ch = split(//, $chars);
    my $max_index = $#ch;
    return if $max_index > 65535;

    my $mask;
    for my $n (1..31) {
        $mask = (1 << $n) - 1;
        last if $mask >= $max_index;
    }

    my $upck = ($max_index > 255) ? 'n*' : 'C*';
    my $l = $len * 2;
    my $rv = '';
    my @r;
    my $ri = 0;
    while (length($rv) < $len) {
        if ($ri >= @r) {
            @r = unpack($upck, $self->bytes($l));
            $ri = 0;
        }
        my $i = $r[$ri++] & $mask;
        next if $i > $max_index;
        $rv .= $ch[$i];
    }
    return $rv;
}

{
    my $RNG_object;
    my $fetch_RNG = sub {
        $RNG_object = Crypt::PRNG->new unless defined $RNG_object && ref($RNG_object) ne 'SCALAR';
        return $RNG_object;
    };

    sub rand(;$)                { return $fetch_RNG->()->double(@_) }
    sub irand()                 { return $fetch_RNG->()->int32() }
    sub random_bytes($)         { return $fetch_RNG->()->bytes(@_) }
    sub random_bytes_hex($)     { return $fetch_RNG->()->bytes_hex(@_) }
    sub random_bytes_b64($)     { return $fetch_RNG->()->bytes_b64(@_) }
    sub random_bytes_b64u($)    { return $fetch_RNG->()->bytes_b64u(@_) }
    sub random_string_from($;$) { return $fetch_RNG->()->string_from(@_) }
    sub random_string(;$)       { return $fetch_RNG->()->string(@_) }
}

1;
