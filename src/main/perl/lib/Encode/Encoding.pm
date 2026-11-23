package Encode::Encoding;
use strict;
use warnings;
our $VERSION = '2.08';

# Base class for encoding implementations.  Java encodings are handled by
# Encode.java, while pure-Perl codecs register their own objects here.

sub Define {
    my ($class, $canonical, @aliases) = @_;
    require Encode;
    my $encoding = ref($class) ? $class : bless { Name => $canonical }, $class;
    Encode::define_encoding($encoding, $canonical, @aliases);
    return $encoding;
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
