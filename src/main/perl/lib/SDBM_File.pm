package SDBM_File;

use strict;
use warnings;
use Fcntl qw(O_CREAT O_RDWR);
use Tie::Hash;

our @ISA = qw(Tie::StdHash);
our $VERSION = '1.14';

sub TIEHASH {
    my ($class, $filename, $flags, $mode) = @_;
    return unless defined $filename && length $filename;

    # The bundled shim historically accepted a zero flag for a new database;
    # retain that compatibility while still honoring read-only opens of an
    # existing file.
    $flags = O_CREAT | O_RDWR
        unless defined $flags && ($flags != 0 || -e $filename);
    $mode = 0666 unless defined $mode;
    sysopen(my $probe, $filename, $flags, $mode) or return;
    close $probe;

    my $self = bless {
        filename => $filename,
        data     => {},
        dirty    => 0,
    }, $class;
    $self->_load;
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
    return 1 unless $self->{dirty};

    open my $fh, '>', $filename or return;
    for my $key (sort keys %{ $self->{data} }) {
        print {$fh} _encode($key), "\t", _encode($self->{data}{$key}), "\n";
    }
    close $fh;
    $self->{dirty} = 0;
    return 1;
}

sub FETCH { $_[0]{data}{ $_[1] } }

sub STORE {
    my ($self, $key, $value) = @_;
    $self->{data}{$key} = $value;
    $self->{dirty} = 1;
}

sub DELETE {
    my ($self, $key) = @_;
    my $value = delete $self->{data}{$key};
    $self->{dirty} = 1;
    return $value;
}

sub CLEAR {
    my ($self) = @_;
    %{ $self->{data} } = ();
    $self->{dirty} = 1;
}

sub EXISTS { exists $_[0]{data}{ $_[1] } }
sub FIRSTKEY { my $reset = scalar keys %{ $_[0]{data} }; each %{ $_[0]{data} } }
sub NEXTKEY { each %{ $_[0]{data} } }
sub SCALAR { scalar %{ $_[0]{data} } }

sub filter_store_key { $_[0]{filter_store_key} = $_[1]; return $_[0] }
sub filter_store_value { $_[0]{filter_store_value} = $_[1]; return $_[0] }
sub filter_fetch_key { $_[0]{filter_fetch_key} = $_[1]; return $_[0] }
sub filter_fetch_value { $_[0]{filter_fetch_value} = $_[1]; return $_[0] }

sub sync { $_[0]->_flush }
sub UNTIE { $_[0]->_flush }
sub DESTROY { $_[0]->_flush }

1;
