package Compress::Zlib;

#
# Original Compress::Zlib module by Paul Marquess <pmqs@cpan.org>
# Copyright (c) 1995-2025 Paul Marquess. All rights reserved.
#
# This program is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/CompressZlib.java
#

use strict;
use warnings;

our $VERSION = '2.219';

use Exporter;
our @ISA = qw(Exporter);

XSLoader::load('Compress::Zlib');

our @EXPORT = qw(
    compress
    uncompress
    memGzip
    memGunzip
    crc32
    adler32
    inflateInit
    deflateInit
    gzopen
    Z_OK
    Z_STREAM_END
    Z_STREAM_ERROR
    Z_DATA_ERROR
    Z_BUF_ERROR
    Z_NO_FLUSH
    Z_SYNC_FLUSH
    Z_FULL_FLUSH
    Z_FINISH
    Z_DEFAULT_COMPRESSION
    Z_BEST_SPEED
    Z_BEST_COMPRESSION
    Z_FILTERED
    Z_HUFFMAN_ONLY
    Z_DEFAULT_STRATEGY
    Z_DEFLATED
    WANT_GZIP
    WANT_GZIP_OR_ZLIB
    MAX_WBITS
);

our @EXPORT_OK = @EXPORT;

1;
