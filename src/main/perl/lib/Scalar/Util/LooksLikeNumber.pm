package Scalar::Util::LooksLikeNumber;

use strict;

our $VERSION    = "1.39.2";

require Exporter;
our @ISA       = qw(Exporter);
our @EXPORT_OK = qw(looks_like_number);

require XSLoader;
XSLoader::load('Scalar::Util::LooksLikeNumber', $VERSION);

1;

=head1 NAME

Scalar::Util::LooksLikeNumber - Access to looks_like_number() perl API function

=head1 SYNOPSIS

    use Scalar::Util::LooksLikeNumber;
    print Scalar::Util::LooksLikeNumber::looks_like_number(1); # -> 4352


=head1 DESCRIPTION

C<Scalar::Util::LooksLikeNumber> contains looks_like_number() like
C<Scalar::Util>'s looks_like_number(), except it returns the raw value from the
C function. Scalar::Util used to do this also, but it returns a booleanized
value since 1.39.


=head1 FUNCTIONS

=head2 $res = looks_like_number( $var )

Returns a non-zero if perl thinks C<$var> is a number. See
L<perlapi/looks_like_number>.


=head1 SEE ALSO

L<Scalar::Util>


=head1 COPYRIGHT

Copyright (c) 2014 Steven Haryanto &lt;stevenharyanto@gmail.com&gt;. Code is
based on Scalar::Util.

=cut
