package Digest::JHash;

use strict;
use warnings;

require 5.008;
require Exporter;
require XSLoader;

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(jhash);
our $VERSION = '0.10';

XSLoader::load('Digest::JHash', $VERSION);

1;

__END__

=head1 NAME

Digest::JHash - 32-bit Jenkins hashing for PerlOnJava

=head1 DESCRIPTION

This is a PerlOnJava port of Digest::JHash. The original Perl interface is
retained and its XS implementation is replaced by an equivalent Java method.

=head1 AUTHORS

The JHash implementation was written by Bob Jenkins. The original Perl
extension was written by Andrew Towers, with modifications by James Freeman.

=head1 LICENSE

This package may be used, redistributed, and modified under the Artistic
License 2.0, matching the original distribution.

=cut
