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

our $VERSION = '2.220';

use Exporter;
our @ISA = qw(Exporter);

XSLoader::load('Compress::Zlib');

our $gzerrno = 0;

our @EXPORT = qw(
    $gzerrno
    compress
    uncompress
    memGzip
    memGunzip
    zlib_version
    crc32
    adler32
    inflateInit
    deflateInit
    gzopen
    ZLIB_VERSION
    ZLIB_VERNUM
    Z_OK
    Z_STREAM_END
    Z_NEED_DICT
    Z_ERRNO
    Z_STREAM_ERROR
    Z_DATA_ERROR
    Z_MEM_ERROR
    Z_BUF_ERROR
    Z_VERSION_ERROR
    Z_NO_COMPRESSION
    Z_NO_FLUSH
    Z_PARTIAL_FLUSH
    Z_SYNC_FLUSH
    Z_FULL_FLUSH
    Z_FINISH
    Z_BLOCK
    Z_TREES
    Z_DEFAULT_COMPRESSION
    Z_BEST_SPEED
    Z_BEST_COMPRESSION
    Z_FILTERED
    Z_HUFFMAN_ONLY
    Z_RLE
    Z_FIXED
    Z_DEFAULT_STRATEGY
    Z_DEFLATED
    Z_NULL
    Z_ASCII
    Z_BINARY
    Z_UNKNOWN
    WANT_GZIP
    WANT_GZIP_OR_ZLIB
    MAX_WBITS
    MAX_MEM_LEVEL
    DEF_WBITS
    OS_CODE
);

our @EXPORT_OK = @EXPORT;
our %EXPORT_TAGS = (
    ALL => \@EXPORT_OK,
    all => \@EXPORT_OK,
);

1;
