package Scalar::Type;

use strict;
use warnings;
use Carp qw(croak);
use Config;
use Scalar::Util qw(blessed);
use base qw(Exporter);

our $VERSION = '1.0.1';
our $IS_BOOL_FN = 'builtin::is_bool';

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

our @EXPORT_OK = qw(type sizeof is_integer is_number bool_supported is_bool);
our %EXPORT_TAGS = (
    all    => \@EXPORT_OK,
    'is_*' => [qw(is_integer is_number is_bool)],
);

sub import {
    __PACKAGE__->export_to_level(1, map { $_ eq 'is_*' ? ':is_*' : $_ } @_);
}

sub bool_supported { $IS_BOOL_FN }

sub type {
    croak(__PACKAGE__ . '::type requires an argument') unless @_;
    return blessed($_[0])          ? blessed($_[0])
         : ref($_[0])              ? 'REF_TO_' . ref($_[0])
         : !defined($_[0])         ? 'UNDEF'
         : builtin::is_bool($_[0]) ? 'BOOL'
         :                           _scalar_type($_[0]);
}

sub sizeof {
    croak(__PACKAGE__ . '::sizeof requires an argument') unless @_;
    my $arg = $_[0];
    my $type = type($_[0]);
    return $Config{ivsize} if $type eq 'INTEGER';
    return $Config{nvsize} if $type eq 'NUMBER';
    croak(__PACKAGE__ . "::sizeof: '$arg' isn't numeric: $type\n");
}

sub is_integer {
    croak(__PACKAGE__ . '::is_integer requires an argument') unless @_;
    return type(@_) eq 'INTEGER' ? 1 : 0;
}

sub is_number {
    croak(__PACKAGE__ . '::is_number requires an argument') unless @_;
    return is_integer(@_) || type(@_) eq 'NUMBER' ? 1 : 0;
}

sub is_bool {
    croak(__PACKAGE__ . '::is_bool requires an argument') unless @_;
    return type(@_) eq 'BOOL';
}

1;

__END__

=head1 NAME

Scalar::Type - figure out what type a scalar is

=head1 DESCRIPTION

This is the PerlOnJava port of Scalar::Type 1.0.1. The public Perl API is
preserved and the original XS scalar-flag probe is implemented by the
PerlOnJava runtime.

=head1 AUTHOR, COPYRIGHT AND LICENCE

Copyright 2024 David Cantrell E<lt>david@cantrell.org.ukE<gt>

This software may be distributed and modified under either the GNU General
Public Licence version 2 or the Artistic Licence.

=cut
