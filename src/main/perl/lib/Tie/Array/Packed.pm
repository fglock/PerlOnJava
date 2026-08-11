package Tie::Array::Packed;

our $VERSION = '0.13';

use strict;
use warnings;
use Carp;

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

my @short = qw(c C F f d i I j J s! S! l! L! n N v V q Q e E);
my %map = (
    Char => 'c', UnsignedChar => 'C', Hex => 'h', NV => 'F', Number => 'F',
    FloatNative => 'f', DoubleNative => 'd', Integer => 'j', UnsignedInteger => 'J',
    IntegerPerl => 'j', IV => 'j', UnsignedIntegerPerl => 'J', UV => 'J',
    IntegerNative => 'i', UnsignedIntegerNative => 'I', ShortNative => 's!',
    UnsignedShortNative => 'S!', LongNative => 'l!', UnsignedLongNative => 'L!',
    UnsignedShortNet => 'n', UnsignedShortBE => 'n', UnsignedLongNet => 'N',
    UnsignedLongBE => 'N', UnsignedShortVax => 'v', UnsignedShortLE => 'v',
    UnsignedLongVax => 'V', UnsignedLongLE => 'V', Quad => 'q', UnsignedQuad => 'Q',
    LongLong => 'q', UnsignedLongLong => 'Q', Int64 => 'q', UInt64 => 'Q',
    Int128 => 'e', UInt128 => 'E',
);
@map{@short} = @short;

for my $name (keys %map) {
    my $type = $map{$name};
    no strict 'refs';
    @{"Tie::Array::Packed::${name}::ISA"} = __PACKAGE__;
    *{"Tie::Array::Packed::${name}::TIEARRAY"} = sub {
        my $class = shift;
        my $self = TIEARRAY($class, $type, defined $_[0] ? $_[0] : '');
        if (@_ > 1) {
            shift;
            $self->SPLICE(0, scalar(@_), @_);
        }
        return $self;
    };
}

sub make {
    my $class = shift;
    tie my @self, $class, '', @_;
    return \@self;
}

sub make_with_packed {
    my $class = shift;
    tie my @self, $class, @_;
    return \@self;
}

sub make_clone {
    my $self = shift;
    tie my @clone, ref($self), $$self;
    return \@clone;
}

sub string { ${$_[0]} }

my $sort_packed_loaded;
sub _load_sort_packed {
    eval { require Sort::Packed };
    croak __PACKAGE__ . '::sort requires package Sort::Packed'
        if $@ or !$Sort::Packed::VERSION;
    $sort_packed_loaded++;
}

sub sort {
    @_ > 2 and croak 'Usage: tied(@parray)->sort([sub { CMP($a, $b) }])';
    $sort_packed_loaded or _load_sort_packed();
    my $self = shift;
    my $packer = $self->packer;
    return @_ ? Sort::Packed::sort_packed_custom(shift, $packer, $$self)
              : Sort::Packed::sort_packed($packer, $$self);
}

sub shuffle {
    @_ == 1 or croak 'Usage: tied(@parray)->shuffle';
    $sort_packed_loaded or _load_sort_packed();
    my $self = shift;
    return Sort::Packed::shuffle_packed($self->packer, $$self);
}

sub grep {
    @_ == 2 or croak 'Usage: tied(@parray)->grep(sub { SELECT($_) })';
    my ($self, $select) = @_;
    my $last = $self->FETCHSIZE - 1;
    my $slow = 0;
    for my $i (0 .. $last) {
        for ($self->FETCH($i)) {
            my $copy = $_;
            if (&$select) {
                $self->STORE($slow, $copy) if $slow < $i;
                $slow++;
            }
        }
    }
    $self->STORESIZE($slow);
    return $slow;
}

1;

__END__

=head1 NAME

Tie::Array::Packed - store arrays efficiently as packed strings

=head1 DESCRIPTION

This is the PerlOnJava port of Tie::Array::Packed 0.13.  The original Perl
interface is retained and its XS storage operations are implemented in Java.

=head1 COPYRIGHT AND LICENSE

Copyright (C) 2006-2008, 2011-2013 by Salvador Fandino
(sfandino@yahoo.com).

Some parts copied from Tie::Array::PackedC (C) 2003-2006 by Yves Orton.

This library is free software; you can redistribute it and/or modify it under
the same terms as Perl itself, either Perl version 5.8.8 or, at your option,
any later version of Perl 5 you may have available.

=cut
