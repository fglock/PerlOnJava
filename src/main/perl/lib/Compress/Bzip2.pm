package Compress::Bzip2;

#
# Original Compress::Bzip2 module by Rob Janes.
# Copyright (c) 2005 Rob Janes. All rights reserved.
# This program is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in:
#   src/main/java/org/perlonjava/runtime/perlmodule/CompressBzip2.java
#   src/main/java/org/perlonjava/runtime/perlmodule/CompressBzip2BzFile.java
# It is backed by Apache Commons Compress (BZip2CompressorInputStream /
# BZip2CompressorOutputStream).
#

use strict;
use warnings;

our $VERSION = '2.28';

require Exporter;
our @ISA = qw(Exporter);

XSLoader::load('Compress::Bzip2');

our %EXPORT_TAGS = (
    'constants' => [ qw(
        BZ_CONFIG_ERROR BZ_DATA_ERROR BZ_DATA_ERROR_MAGIC
        BZ_FINISH BZ_FINISH_OK BZ_FLUSH BZ_FLUSH_OK
        BZ_IO_ERROR BZ_MAX_UNUSED BZ_MEM_ERROR
        BZ_OK BZ_OUTBUFF_FULL BZ_PARAM_ERROR
        BZ_RUN BZ_RUN_OK BZ_SEQUENCE_ERROR
        BZ_STREAM_END BZ_UNEXPECTED_EOF
    ) ],
    'utilities' => [ qw(
        memBzip memBunzip bzip2 bzunzip
    ) ],
    'bzip1'  => [ qw(bzopen bzclose bzread bzreadline bzwrite bzeof bzerror) ],
    'gzip'   => [ qw(bzopen bzclose bzread bzreadline bzwrite bzeof bzerror) ],
);
our @EXPORT_OK = ( map { @$_ } values %EXPORT_TAGS );
$EXPORT_TAGS{'all'} = [ @EXPORT_OK ];
our @EXPORT = qw();

1;

__END__

=head1 NAME

Compress::Bzip2 - PerlOnJava implementation of Compress::Bzip2

=head1 DESCRIPTION

Provides bzip2 compression and decompression backed by the Apache Commons
Compress Java library. The Perl-visible API matches the upstream
L<Compress::Bzip2> CPAN module: one-shot helpers (C<memBzip>, C<memBunzip>,
C<bzip2>, C<bzunzip>) and a file-handle interface returned by C<bzopen>
with C<bzread>, C<bzreadline>, C<bzwrite>, C<bzclose>, C<bzeof>, and
C<bzerror>.

=cut
