package Encode::Locale;

use strict;
our $VERSION = "1.05";

use base 'Exporter';
our @EXPORT_OK = qw(
    decode_argv env
    $ENCODING_LOCALE $ENCODING_LOCALE_FS
    $ENCODING_CONSOLE_IN $ENCODING_CONSOLE_OUT
);

# ExtUtils::MakeMaker ships a maintained derivative of Encode::Locale.  Reuse
# that bundled pure-Perl implementation so the CPAN client and LWP have locale
# encoding support without another native or Java implementation.
require ExtUtils::MakeMaker::Locale;

our ($ENCODING_LOCALE, $ENCODING_LOCALE_FS,
     $ENCODING_CONSOLE_IN, $ENCODING_CONSOLE_OUT);

{
    no strict 'refs';
    *ENCODING_LOCALE = *ExtUtils::MakeMaker::Locale::ENCODING_LOCALE;
    *ENCODING_LOCALE_FS = *ExtUtils::MakeMaker::Locale::ENCODING_LOCALE_FS;
    *ENCODING_CONSOLE_IN = *ExtUtils::MakeMaker::Locale::ENCODING_CONSOLE_IN;
    *ENCODING_CONSOLE_OUT = *ExtUtils::MakeMaker::Locale::ENCODING_CONSOLE_OUT;
    *decode_argv = \&ExtUtils::MakeMaker::Locale::decode_argv;
    *env = \&ExtUtils::MakeMaker::Locale::env;
    *reinit = \&ExtUtils::MakeMaker::Locale::reinit;
}

1;

__END__

=head1 NAME

Encode::Locale - Determine the locale encoding

=head1 DESCRIPTION

This PerlOnJava port exposes the Encode::Locale 1.05 interface through the
newer pure-Perl implementation bundled with ExtUtils::MakeMaker.  It provides
the C<locale>, C<locale_fs>, C<console_in>, and C<console_out> Encode aliases,
as well as C<decode_argv>, C<env>, and C<reinit>.

=head1 AUTHOR AND LICENSE

The original Encode::Locale module is copyright 2010 Gisle Aas.

This library is free software; you can redistribute it and/or modify it under
the same terms as Perl itself.

=cut
