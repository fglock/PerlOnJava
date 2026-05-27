package Compress::Raw::Bzip2;

use strict;
use warnings;
use bytes;

require 5.006;
require Exporter;
use Carp ();

our ($VERSION, $XS_VERSION, @ISA, @EXPORT, $AUTOLOAD);

$VERSION = '2.218';
$XS_VERSION = $VERSION;
$VERSION = eval $VERSION;

@ISA = qw(Exporter);
@EXPORT = qw(
    BZ_RUN
    BZ_FLUSH
    BZ_FINISH
    BZ_OK
    BZ_RUN_OK
    BZ_FLUSH_OK
    BZ_FINISH_OK
    BZ_STREAM_END
    BZ_SEQUENCE_ERROR
    BZ_PARAM_ERROR
    BZ_MEM_ERROR
    BZ_DATA_ERROR
    BZ_DATA_ERROR_MAGIC
    BZ_IO_ERROR
    BZ_UNEXPECTED_EOF
    BZ_OUTBUFF_FULL
    BZ_CONFIG_ERROR
);

sub AUTOLOAD {
    my ($constname);
    ($constname = $AUTOLOAD) =~ s/.*:://;
    my ($error, $val) = constant($constname);
    Carp::croak $error if $error;
    no strict 'refs';
    *{$AUTOLOAD} = sub { $val };
    goto &{$AUTOLOAD};
}

use constant FLAG_APPEND        => 1;
use constant FLAG_CRC           => 2;
use constant FLAG_ADLER         => 4;
use constant FLAG_CONSUME_INPUT => 8;

eval {
    require XSLoader;
    XSLoader::load('Compress::Raw::Bzip2', $XS_VERSION);
    1;
}
or do {
    require DynaLoader;
    local @ISA = qw(DynaLoader);
    bootstrap Compress::Raw::Bzip2 $XS_VERSION;
};

sub Compress::Raw::Bzip2::STORABLE_freeze {
    my $type = ref shift;
    Carp::croak "Cannot freeze $type object\n";
}

sub Compress::Raw::Bzip2::STORABLE_thaw {
    my $type = ref shift;
    Carp::croak "Cannot thaw $type object\n";
}

sub Compress::Raw::Bunzip2::STORABLE_freeze {
    my $type = ref shift;
    Carp::croak "Cannot freeze $type object\n";
}

sub Compress::Raw::Bunzip2::STORABLE_thaw {
    my $type = ref shift;
    Carp::croak "Cannot thaw $type object\n";
}

1;

__END__

=head1 NAME

Compress::Raw::Bzip2 - Low-level bzip2 interface for PerlOnJava

=head1 DESCRIPTION

PerlOnJava-compatible shim for the CPAN C<Compress::Raw::Bzip2> API.
The implementation is provided by
C<org.perlonjava.runtime.perlmodule.CompressRawBzip2> and reuses the
Apache Commons Compress-backed C<Compress::Bzip2> byte helpers.

=cut
