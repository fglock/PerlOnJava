package Encode::Encoding;
use strict;
use warnings;
our $VERSION = '2.08';

# Minimal stub for Encode::Encoding base class.
# PerlOnJava handles encoding/decoding natively in Java,
# so this only provides the class hierarchy that Encode::Guess expects.

sub Define {
    # no-op: encoding registration handled by Java-side Encode
}

sub new {
    my ($class, %opts) = @_;
    return bless \%opts, $class;
}

sub name { return $_[0]->{Name} || ref($_[0]) }

sub mime_name { return undef }

sub renew {
    my $self = shift;
    my $clone = bless {%$self}, ref $self;
    return $clone;
}

sub perlio_ok { return 0 }

sub needs_lines { return 0 }

1;
