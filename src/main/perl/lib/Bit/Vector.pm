package Bit::Vector;

use strict;
use warnings;
use Math::BigInt;

our $VERSION = '7.4';

sub Version   { '7.4' }
sub Word_Bits { 64 }
sub Long_Bits { 64 }

sub new { shift->Create(@_) }

sub Create {
    my ($class, $bits, $count) = @_;
    _check_size($bits);
    $count = 1 unless defined $count;

    my @vectors = map {
        bless { bits => 0 + $bits, value => Math::BigInt->new(0) }, $class
    } 1 .. $count;

    return wantarray ? @vectors : $vectors[-1];
}

sub new_Dec {
    my ($class, $bits, $value) = @_;
    my $self = $class->Create($bits);
    $self->from_Dec($value);
    return $self;
}

sub Size { $_[0]->{bits} }

sub from_Dec {
    my ($self, $value) = @_;
    die "Bit::Vector::from_Dec(): not a decimal number\n"
        unless defined($value) && "$value" =~ /\A[+-]?\d+\z/;

    my $modulus = Math::BigInt->new(2)->bpow($self->{bits});
    my $number = Math::BigInt->new("$value")->bmod($modulus);
    $number->badd($modulus) if $number->is_neg;
    $self->{value} = $number;
    return $self;
}

sub to_Dec {
    my ($self) = @_;
    return '0' unless $self->{bits};

    my $number = $self->{value}->copy;
    my $sign = Math::BigInt->new(2)->bpow($self->{bits} - 1);
    if ($number->bcmp($sign) >= 0) {
        $number->bsub($sign->copy->bmul(2));
    }
    return "$number";
}

sub Concat {
    my ($self, $other) = @_;
    return $self->Concat_List($other);
}

sub Concat_List {
    my ($self, @others) = @_;
    my $class = ref($self) || $self;
    my $bits = $self->{bits};
    my $value = $self->{value}->copy;

    for my $other (@others) {
        die "Bit::Vector::Concat_List(): not a Bit::Vector object\n"
            unless ref($other) && $other->isa('Bit::Vector');
        $value->blsft($other->{bits})->badd($other->{value});
        $bits += $other->{bits};
    }

    return bless { bits => $bits, value => $value }, $class;
}

sub Chunk_Read {
    my ($self, $chunk_bits, $offset) = @_;
    _check_size($chunk_bits);
    die "Bit::Vector::Chunk_Read(): offset out of range\n"
        unless defined($offset) && $offset =~ /\A\d+\z/
            && $offset + $chunk_bits <= $self->{bits};

    my $mask = Math::BigInt->new(2)->bpow($chunk_bits)->bdec;
    my $chunk = $self->{value}->copy->brsft($offset)->band($mask);
    return 0 + "$chunk";
}

sub _check_size {
    my ($bits) = @_;
    die "Bit::Vector: bit count must be a non-negative integer\n"
        unless defined($bits) && "$bits" =~ /\A\d+\z/;
}

1;

__END__

=head1 NAME

Bit::Vector - portable PerlOnJava compatibility core

=head1 DESCRIPTION

This implementation supplies the fixed-width construction, decimal conversion,
concatenation, and chunk-reading operations used by C<Net::Frame>.  It uses
C<Math::BigInt> so values are not limited to a Java or Perl machine word.

=cut
