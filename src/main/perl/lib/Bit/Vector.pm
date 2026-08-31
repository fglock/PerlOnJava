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
        bless {
            bits     => 0 + $bits,
            value    => Math::BigInt->new(0),
            set_bits => {},
        }, $class
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
    _set_bits_from_value($self);
    return $self;
}

sub to_Dec {
    my ($self) = @_;
    return '0' unless $self->{bits};

    _sync_value_from_bits($self);
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
    _sync_value_from_bits($self);
    my $value = $self->{value}->copy;
    my %set_bits = %{ $self->{set_bits} };

    for my $other (@others) {
        die "Bit::Vector::Concat_List(): not a Bit::Vector object\n"
            unless ref($other) && $other->isa('Bit::Vector');
        _sync_value_from_bits($other);
        $value->blsft($other->{bits})->badd($other->{value});
        %set_bits = map { $_ + $other->{bits} => 1 } keys %set_bits;
        $set_bits{$_} = 1 for keys %{ $other->{set_bits} };
        $bits += $other->{bits};
    }

    return bless { bits => $bits, value => $value, set_bits => \%set_bits }, $class;
}

sub Chunk_Read {
    my ($self, $chunk_bits, $offset) = @_;
    _check_size($chunk_bits);
    die "Bit::Vector::Chunk_Read(): offset out of range\n"
        unless defined($offset) && $offset =~ /\A\d+\z/
            && $offset + $chunk_bits <= $self->{bits};

    _sync_value_from_bits($self);
    my $mask = Math::BigInt->new(2)->bpow($chunk_bits)->bdec;
    my $chunk = $self->{value}->copy->brsft($offset)->band($mask);
    return 0 + "$chunk";
}

sub Bit_Off {
    my ($self, $index) = @_;
    $index = _check_index('Bit_Off', $self, $index);
    delete $self->{set_bits}{$index};
    return;
}

sub Bit_On {
    my ($self, $index) = @_;
    $index = _check_index('Bit_On', $self, $index);
    $self->{set_bits}{$index} = 1;
    return;
}

sub Empty {
    $_[0]->{set_bits} = {};
    return;
}

sub Fill {
    my ($self) = @_;
    $self->{set_bits} = { map { $_ => 1 } 0 .. $self->{bits} - 1 };
    return;
}

sub Interval_Fill {
    my ($self, $lower, $upper) = @_;
    $lower = _check_index('Interval_Fill', $self, $lower);
    $upper = _check_index('Interval_Fill', $self, $upper);
    die "Bit::Vector::Interval_Fill(): minimum greater than maximum\n"
        if $lower > $upper;
    $self->{set_bits}{$_} = 1 for $lower .. $upper;
    return;
}

sub AndNot {
    my ($self, $left, $right) = @_;
    $self->{set_bits} = {
        map { $_ => 1 } grep { !$right->{set_bits}{$_} } keys %{ $left->{set_bits} }
    };
    return;
}

sub And {
    my ($self, $left, $right) = @_;
    $self->{set_bits} = {
        map { $_ => 1 } grep { $right->{set_bits}{$_} } keys %{ $left->{set_bits} }
    };
    return;
}

sub Norm { scalar keys %{ $_[0]->{set_bits} } }

sub Interval_Scan_dec {
    my ($self, $index) = @_;
    $index = _check_index('Interval_Scan_dec', $self, $index);
    return unless $self->{set_bits}{$index};
    my $min = $index;
    --$min while $min > 0 && $self->{set_bits}{ $min - 1 };
    return ($min, $index);
}

sub Interval_Scan_inc {
    my ($self, $index) = @_;
    $index = _check_index('Interval_Scan_inc', $self, $index);
    return unless $self->{set_bits}{$index};
    my $max = $index;
    ++$max while $max + 1 < $self->{bits} && $self->{set_bits}{ $max + 1 };
    return ($index, $max);
}

sub bit_flip {
    my ($self, $index) = @_;
    $index = _check_index('bit_flip', $self, $index);
    $self->{set_bits}{$index}
        ? delete $self->{set_bits}{$index}
        : ($self->{set_bits}{$index} = 1);
    return $self->bit_test($index);
}

sub flip { shift->bit_flip(@_) }

sub bit_test {
    my ($self, $index) = @_;
    $index = _check_index('bit_test', $self, $index);
    return $self->{set_bits}{$index} ? 1 : 0;
}

sub contains { shift->bit_test(@_) }
sub in       { shift->bit_test(@_) }

sub Bit_Copy {
    my ($self, $index, $bit) = @_;
    $index = _check_index('Bit_Copy', $self, $index);
    $bit ? $self->Bit_On($index) : $self->Bit_Off($index);
    return;
}

sub LSB {
    my $self = shift;
    $self->Bit_Copy(0, @_);
}

sub MSB {
    my $self = shift;
    $self->Bit_Copy($self->{bits} - 1, @_);
}

sub lsb { shift->bit_test(0) }
sub msb {
    my $self = shift;
    $self->bit_test($self->{bits} - 1);
}

sub _check_index {
    my ($method, $self, $index) = @_;
    $index = 0 + $index;
    die "Bit::Vector::$method(): index out of range\n"
        unless $index >= 0 && $index < $self->{bits};
    return int $index;
}

sub _set_bits_from_value {
    my ($self) = @_;
    my $binary = $self->{value}->as_bin;
    $binary =~ s/^0b//;
    my %set_bits;
    for my $index (0 .. length($binary) - 1) {
        $set_bits{$index} = 1 if substr($binary, -1 - $index, 1) eq '1';
    }
    $self->{set_bits} = \%set_bits;
}

sub _sync_value_from_bits {
    my ($self) = @_;
    my $value = Math::BigInt->new(0);
    $value->bior(Math::BigInt->new(2)->bpow($_)) for keys %{ $self->{set_bits} };
    $self->{value} = $value;
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
