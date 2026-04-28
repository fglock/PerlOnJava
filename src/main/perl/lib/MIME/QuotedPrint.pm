package MIME::QuotedPrint;

#
# Original MIME::QuotedPrint module by Gisle Aas.
# Copyright 1995-1997, 2002-2004 Gisle Aas.
#
# This library is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/MimeQuotedPrint.java
#

our $VERSION = '3.16';

use XSLoader;
XSLoader::load( 'MIME::QuotedPrint' );

*encode = \&encode_qp;
*decode = \&decode_qp;

1;

__END__

=head1 NAME

MIME::QuotedPrint - Encoding and decoding of quoted-printable strings

=head1 DESCRIPTION

This is the PerlOnJava implementation of MIME::QuotedPrint. The actual implementation
is in the Java backend.

=head1 AUTHOR

Original module by Gisle Aas.

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT

Copyright 1995-1997, 2002-2004 Gisle Aas.

This library is free software; you can redistribute it and/or
modify it under the same terms as Perl itself.

=cut

