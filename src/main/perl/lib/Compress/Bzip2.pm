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
        memBzip memBunzip bzip2 bzunzip bzinflateInit bzdeflateInit
    ) ],
    'bzip1'  => [ qw(bzopen bzclose bzread bzreadline bzwrite bzeof bzerror) ],
    'gzip'   => [ qw(bzopen bzclose bzread bzreadline bzwrite bzeof bzerror) ],
);
our @EXPORT_OK = ( map { @$_ } values %EXPORT_TAGS );
$EXPORT_TAGS{'all'} = [ @EXPORT_OK ];
our @EXPORT = qw();

# Compress::Bzip2 exposes a small streaming writer API in addition to its
# one-shot helpers. The Java raw backend already owns the buffered compressor;
# this wrapper adapts its output-parameter API to Compress::Bzip2's
# return-bytes convention.
sub bzdeflateInit {
    require Compress::Raw::Bzip2;
    my $raw = Compress::Raw::Bzip2->new(1);
    return unless $raw;
    return bless { raw => $raw }, 'Compress::Bzip2::bzdeflateStream';
}

# Compress::Bzip2 also exposes its bzip2 reader through the historical
# Compress::Zlib-compatible inflateInit API.  Keep the facade here so callers
# such as Net::Async::HTTP can use the bundled raw streaming implementation
# without depending on its output-parameter interface.
sub bzinflateInit {
    require Compress::Raw::Bzip2;
    my $raw = Compress::Raw::Bunzip2->new(1);
    return unless $raw;
    return bless { raw => $raw }, 'Compress::Bzip2::bzinflateStream';
}

sub inflateInit {
    return bzinflateInit(@_);
}

package Compress::Bzip2::bzdeflateStream;

sub bzdeflate {
    my ($self, $input) = @_;
    my $output = '';
    $self->{raw}->bzdeflate($input, $output);
    return $output;
}

sub bzclose {
    my ($self) = @_;
    my $output = '';
    $self->{raw}->bzclose($output);
    return $output;
}

package Compress::Bzip2::bzinflateStream;

sub bzinflate {
    my ($self, $input) = @_;
    my $output = '';
    my $status = $self->{raw}->bzinflate($input, $output);
    return wantarray ? (undef, $status) : undef if $status < 0;
    return wantarray ? ($output, $status) : $output;
}

sub inflate {
    goto &bzinflate;
}

sub bzerror {
    return $_[0]->{raw}->status;
}

sub gzerror {
    goto &bzerror;
}

package Compress::Bzip2::bzFile;

sub read {
    my $self = $_[0];
    my $size = $_[2];
    $size = 4096 unless defined $size;
    my $n = $self->bzread($_[1], $size);
    return undef if !defined($n) || $n < 0;
    return $n;
}

sub close {
    my $self = shift;
    return $self->bzclose == 0;
}

sub print {
    my $self = shift;
    my $data = join '', @_;
    return $self->bzwrite($data) == length($data);
}

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
