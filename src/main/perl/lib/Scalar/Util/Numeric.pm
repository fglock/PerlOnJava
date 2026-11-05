package Scalar::Util::Numeric;

use 5.008000;

use strict;
use warnings;

use base qw(Exporter);
use XSLoader;

our $VERSION = '0.40';

our %EXPORT_TAGS = (
    'all' => [ qw(isbig isfloat isinf isint isnan isneg isnum isuv) ],
);

our @EXPORT_OK = ( map { @$_ } values %EXPORT_TAGS );

XSLoader::load(__PACKAGE__, $VERSION);

1;

__END__

=head1 NAME

Scalar::Util::Numeric - numeric tests for perl scalars

=head1 SYNOPSIS

    use Scalar::Util::Numeric qw(isnum isint isfloat);

    foo($bar / 2) if (isnum $bar);

    if (isint $baz) {
        # ...
    } elsif (isfloat $baz) {
        # ...
    }

=head1 DESCRIPTION

This module exports a number of wrappers around perl's builtin C<grok_number> function, which
returns the numeric type of its argument, or 0 if it isn't numeric.

=head1 TAGS

All of the functions exported by Scalar::Util::Numeric can be imported by using the C<:all> tag:

    use Scalar::Util::Numeric qw(:all);

=head1 EXPORTS

=head2 isnum

    isnum ($val)

Returns a nonzero value (indicating the numeric type) if $val is a number.

The numeric type is a conjunction of the following flags:

    0x01  IS_NUMBER_IN_UV               (number within UV range - not necessarily an integer)
    0x02  IS_NUMBER_GREATER_THAN_UV_MAX (number is greater than UV_MAX)
    0x04  IS_NUMBER_NOT_INT             (saw . or E notation)
    0x08  IS_NUMBER_NEG                 (leading minus sign)
    0x10  IS_NUMBER_INFINITY            (Infinity)
    0x20  IS_NUMBER_NAN                 (NaN - not a number)

=head2 isint

=head2 isuv

=head2 isbig

=head2 isfloat

=head2 isneg

=head2 isinf

=head2 isnan

The following flavours of C<isnum> (corresponding to the flags above) are also available:

    isint
    isuv
    isbig
    isfloat
    isneg
    isinf
    isnan

C<isint> returns -1 if its operand is a negative integer, 1 if
it's 0 or a positive integer, and 0 otherwise.

The others always return 1 or 0.

=head1 SEE ALSO

=over

=item * L<autobox/type>

=item * L<Data::Types>

=item * L<Params::Classify>

=item * L<Params::Util>

=item * L<Scalar::Util>

=item * L<String::Numeric>

=back

=head1 VERSION

0.40

=head1 AUTHORS

=over

=item * chocolateboy <chocolate@cpan.org>

=item * Michael G. Schwern <schwern@pobox.com>

=back

=head1 COPYRIGHT

Copyright (c) 2005-2014, chocolateboy.

This module is free software. It may be used, redistributed
and/or modified under the same terms as Perl itself.

=cut
