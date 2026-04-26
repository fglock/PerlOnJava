package common::sense;

our $VERSION = '3.75';

# PerlOnJava implementation of common::sense.
#
# The CPAN distribution generates sense.pm at install time from sense.pm.PL,
# directly mutating ${^WARNING_BITS} and $^H. PerlOnJava ships a hand-written
# version that uses pragmatic `use strict`, `use warnings`, `use utf8`, and
# `use feature` so it works reliably with the PerlOnJava parser regardless of
# how the script is invoked.

sub import {
    # Equivalent of: use strict;
    strict->import;

    # Equivalent of: use utf8;
    utf8->import;

    # Equivalent of common::sense's warning configuration:
    #   no warnings;
    #   use warnings FATAL => qw(closed threads internal debugging pack
    #                            malloc prototype inplace io pipe unpack
    #                            glob digit printf layer reserved taint
    #                            closure semicolon);
    #   no warnings qw(exec newline unopened);
    warnings->unimport;
    warnings->import(FATAL => qw(
        closed threads internal debugging pack malloc prototype
        inplace io pipe unpack glob digit printf
        layer reserved taint closure semicolon
    ));
    warnings->unimport(qw(exec newline unopened));

    # Equivalent of: use feature qw(say state switch fc evalbytes
    #                               current_sub unicode_strings);
    require feature;
    feature->import(qw(say state switch));
    feature->import(qw(unicode_strings));
    feature->import(qw(current_sub fc evalbytes));
}

sub unimport {
    strict->unimport;
    utf8->unimport;
    warnings->unimport;
    require feature;
    feature->unimport(qw(say state switch fc evalbytes
                         current_sub unicode_strings));
}

1;
