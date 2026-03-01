package Compress::Zlib;
use strict;
use warnings;

our $VERSION = '2.106';

use Exporter;
our @ISA = qw(Exporter);

XSLoader::load('Compress::Zlib');

our @EXPORT = qw(
    inflateInit
    deflateInit
    Z_OK
    Z_STREAM_END
    Z_STREAM_ERROR
    Z_DATA_ERROR
    Z_BUF_ERROR
    MAX_WBITS
);

our @EXPORT_OK = @EXPORT;

1;
