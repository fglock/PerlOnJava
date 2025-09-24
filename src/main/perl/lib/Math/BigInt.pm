package Math::BigInt;
use strict;
use warnings;
use XSLoader;

XSLoader::load('Math::BigInt');

# NOTE: The low-level BigInteger operations are in:
#       src/main/java/org/perlonjava/perlmodule/MathBigInt.java

our $VERSION = '1.999818';

# Export common functions
use Exporter 'import';
our @EXPORT_OK = qw(bgcd blcm);
our @EXPORT = qw();

# Global configuration
our $accuracy = undef;
our $precision = undef;
our $round_mode = 'even';
our $div_scale = 40;

# Constructor - creates a new Math::BigInt object
sub new {
    my ($class, $value) = @_;
    $value = '0' unless defined $value;
    
    # Get BigInteger from Java backend
    my $bigint = Math::BigInt::_new($class, $value);
    
    # Create blessed object
    my $self = {
        value => $bigint,
        sign => Math::BigInt::_sign($class, $bigint),
    };
    
    return bless $self, $class;
}

# String conversion
sub bstr {
    my ($self) = @_;
    return Math::BigInt::_str($self, $self->{value});
}

# Overload string conversion
use overload
    '""' => \&bstr,
    '0+' => sub { 
        my $str = $_[0]->bstr();
        # Convert to number, but preserve precision for very large integers
        if (length($str) > 15) {
            # For very large numbers, return the string to preserve precision
            return $str;
        }
        return 0 + $str;
    },
    '+' => sub {
        my ($x, $y, $swap) = @_;
        $x = $x->copy();
        return $swap ? $x->badd($y) : $x->badd($y);
    },
    '-' => sub {
        my ($x, $y, $swap) = @_;
        $x = $x->copy();
        return $swap ? Math::BigInt->new($y)->bsub($x) : $x->bsub($y);
    },
    '*' => sub {
        my ($x, $y, $swap) = @_;
        $x = $x->copy();
        return $x->bmul($y);
    },
    '/' => sub {
        my ($x, $y, $swap) = @_;
        $x = $x->copy();
        return $swap ? Math::BigInt->new($y)->bdiv($x) : $x->bdiv($y);
    },
    '**' => sub {
        my ($x, $y, $swap) = @_;
        $x = $x->copy();
        return $swap ? Math::BigInt->new($y)->bpow($x) : $x->bpow($y);
    },
    '<=>' => sub {
        my ($x, $y, $swap) = @_;
        return $x->bcmp($y) * ($swap ? -1 : 1);
    },
    'cmp' => sub {
        my ($x, $y, $swap) = @_;
        return $x->bcmp($y) * ($swap ? -1 : 1);
    },
    fallback => 1;

# Copy constructor
sub copy {
    my ($self) = @_;
    return Math::BigInt->new($self->bstr());
}

# Addition
sub badd {
    my ($self, $other) = @_;
    my $other_scalar = ref($other) eq 'Math::BigInt' ? $other->{value} : $other;
    $self->{value} = Math::BigInt::_badd(__PACKAGE__, $self->{value}, $other_scalar);
    $self->{sign} = Math::BigInt::_sign(__PACKAGE__, $self->{value});
    return $self;
}

# Subtraction
sub bsub {
    my ($self, $other) = @_;
    my $other_scalar = ref($other) eq 'Math::BigInt' ? $other->{value} : $other;
    $self->{value} = Math::BigInt::_bsub(__PACKAGE__, $self->{value}, $other_scalar);
    $self->{sign} = Math::BigInt::_sign(__PACKAGE__, $self->{value});
    return $self;
}

# Multiplication
sub bmul {
    my ($self, $other) = @_;
    my $other_scalar = ref($other) eq 'Math::BigInt' ? $other->{value} : $other;
    $self->{value} = Math::BigInt::_bmul(__PACKAGE__, $self->{value}, $other_scalar);
    $self->{sign} = Math::BigInt::_sign(__PACKAGE__, $self->{value});
    return $self;
}

# Division
sub bdiv {
    my ($self, $other) = @_;
    my $other_scalar = ref($other) eq 'Math::BigInt' ? $other->{value} : $other;
    $self->{value} = Math::BigInt::_bdiv(__PACKAGE__, $self->{value}, $other_scalar);
    $self->{sign} = Math::BigInt::_sign(__PACKAGE__, $self->{value});
    return $self;
}

# Power
sub bpow {
    my ($self, $other) = @_;
    my $other_scalar = ref($other) eq 'Math::BigInt' ? $other->{value} : $other;
    $self->{value} = Math::BigInt::_bpow(__PACKAGE__, $self->{value}, $other_scalar);
    $self->{sign} = Math::BigInt::_sign(__PACKAGE__, $self->{value});
    return $self;
}

# Comparison
sub bcmp {
    my ($self, $other) = @_;
    my $other_bigint = ref($other) eq 'Math::BigInt' ? $other->{value} : Math::BigInt::_new(__PACKAGE__, $other);
    return Math::BigInt::_cmp(__PACKAGE__, $self->{value}, $other_bigint);
}

# Test methods
sub is_zero {
    my ($self) = @_;
    return Math::BigInt::_is_zero(__PACKAGE__, $self->{value});
}

sub is_one {
    my ($self, $sign) = @_;
    $sign ||= '+';
    if ($sign eq '-') {
        return $self->bcmp(-1) == 0;
    } else {
        return $self->bcmp(1) == 0;
    }
}

sub is_positive {
    my ($self) = @_;
    return Math::BigInt::_is_positive(__PACKAGE__, $self->{value});
}

sub is_pos { return $_[0]->is_positive(); }

sub is_negative {
    my ($self) = @_;
    return Math::BigInt::_is_negative(__PACKAGE__, $self->{value});
}

sub is_neg { return $_[0]->is_negative(); }

sub is_odd {
    my ($self) = @_;
    return Math::BigInt::_is_odd(__PACKAGE__, $self->{value});
}

sub is_even {
    my ($self) = @_;
    return Math::BigInt::_is_even(__PACKAGE__, $self->{value});
}

sub sign {
    my ($self) = @_;
    return $self->{sign};
}

# Alternative constructors
sub from_dec {
    my ($class, $value) = @_;
    return $class->new($value);
}

sub from_hex {
    my ($class, $value) = @_;
    $value = "0x$value" unless $value =~ /^[+-]?0x/i;
    return $class->new($value);
}

sub from_oct {
    my ($class, $value) = @_;
    $value = "0o$value" unless $value =~ /^[+-]?0o/i;
    return $class->new($value);
}

sub from_bin {
    my ($class, $value) = @_;
    $value = "0b$value" unless $value =~ /^[+-]?0b/i;
    return $class->new($value);
}

# Configuration methods
sub config {
    my ($class, %args) = @_;
    
    if (%args) {
        # Set configuration
        $accuracy = $args{accuracy} if exists $args{accuracy};
        $precision = $args{precision} if exists $args{precision};
        $round_mode = $args{round_mode} if exists $args{round_mode};
        $div_scale = $args{div_scale} if exists $args{div_scale};
    }
    
    return {
        accuracy => $accuracy,
        precision => $precision,
        round_mode => $round_mode,
        div_scale => $div_scale,
        lib => 'Math::BigInt::Java',
        lib_version => '1.0',
        class => $class,
        version => $VERSION,
    };
}

1;

__END__

=head1 NAME

Math::BigInt - Arbitrary size integer math package for PerlOnJava

=head1 SYNOPSIS

    use Math::BigInt;
    
    my $x = Math::BigInt->new('123456789012345678901234567890');
    my $y = Math::BigInt->new('987654321098765432109876543210');
    
    print $x + $y, "\n";  # Addition
    print $x * $y, "\n";  # Multiplication
    print $x ** 2, "\n";  # Power
    
    # Exact arithmetic for large integers
    my $big = Math::BigInt->new(2)->bpow(54)->badd(3);  # 2**54 + 3
    print $big, "\n";  # Preserves exact value

=head1 DESCRIPTION

This module provides arbitrary precision integer arithmetic for PerlOnJava using
Java's BigInteger class as the backend. It preserves exact integer values even
for very large numbers that would lose precision in floating point representation.

=head1 METHODS

=head2 new($value)

Creates a new Math::BigInt object from the given value.

=head2 badd($other), bsub($other), bmul($other), bdiv($other), bpow($other)

Arithmetic operations that modify the object in place.

=head2 bcmp($other)

Comparison method returning -1, 0, or 1.

=head2 is_zero(), is_one(), is_positive(), is_negative(), is_odd(), is_even()

Test methods.

=head1 AUTHOR

PerlOnJava Project

=cut
