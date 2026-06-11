package SDBM_File;

use strict;
use warnings;
use Tie::Hash;

our @ISA = qw(Tie::StdHash);
our $VERSION = '1.14';

sub TIEHASH {
    my ($class, $filename) = @_;
    my $self = bless {
        filename => $filename,
        data     => {},
    }, $class;
    $self->_load;
    $self->_flush unless -e $filename;
    return $self;
}

sub _encode {
    my ($value) = @_;
    $value = '' unless defined $value;
    $value =~ s/%/%25/g;
    $value =~ s/\t/%09/g;
    $value =~ s/\r/%0D/g;
    $value =~ s/\n/%0A/g;
    return $value;
}

sub _decode {
    my ($value) = @_;
    $value = '' unless defined $value;
    $value =~ s/%0A/\n/g;
    $value =~ s/%0D/\r/g;
    $value =~ s/%09/\t/g;
    $value =~ s/%25/%/g;
    return $value;
}

sub _load {
    my ($self) = @_;
    my $filename = $self->{filename};
    return unless defined $filename && -e $filename;

    open my $fh, '<', $filename or return;
    while (defined(my $line = <$fh>)) {
        chomp $line;
        my ($key, $value) = split /\t/, $line, 2;
        $self->{data}{ _decode($key) } = _decode($value);
    }
    close $fh;
}

sub _flush {
    my ($self) = @_;
    my $filename = $self->{filename};
    return unless defined $filename;

    open my $fh, '>', $filename or return;
    for my $key (sort keys %{ $self->{data} }) {
        print {$fh} _encode($key), "\t", _encode($self->{data}{$key}), "\n";
    }
    close $fh;
}

sub FETCH { $_[0]{data}{ $_[1] } }

sub STORE {
    my ($self, $key, $value) = @_;
    $self->{data}{$key} = $value;
    $self->_flush;
}

sub DELETE {
    my ($self, $key) = @_;
    my $value = delete $self->{data}{$key};
    $self->_flush;
    return $value;
}

sub CLEAR {
    my ($self) = @_;
    %{ $self->{data} } = ();
    $self->_flush;
}

sub EXISTS { exists $_[0]{data}{ $_[1] } }
sub FIRSTKEY { my $reset = scalar keys %{ $_[0]{data} }; each %{ $_[0]{data} } }
sub NEXTKEY { each %{ $_[0]{data} } }
sub SCALAR { scalar %{ $_[0]{data} } }

sub filter_store_key { $_[0]{filter_store_key} = $_[1]; return $_[0] }
sub filter_store_value { $_[0]{filter_store_value} = $_[1]; return $_[0] }
sub filter_fetch_key { $_[0]{filter_fetch_key} = $_[1]; return $_[0] }
sub filter_fetch_value { $_[0]{filter_fetch_value} = $_[1]; return $_[0] }

sub UNTIE { $_[0]->_flush }
sub DESTROY { $_[0]->_flush }

1;
