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
