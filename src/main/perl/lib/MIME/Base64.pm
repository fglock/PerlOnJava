package MIME::Base64;

#
# Original MIME::Base64 module by Gisle Aas.
# Copyright 1995-1999, 2001-2004, 2010 Gisle Aas.
#
# This library is free software; you can redistribute it and/or
# modify it under the same terms as Perl itself.
#
# PerlOnJava implementation by Flavio S. Glock.
# The implementation is in: src/main/java/org/perlonjava/perlmodule/MimeBase64.java
#

use XSLoader;
XSLoader::load( 'MIME::Base64' );

*encode = \&encode_base64;
*decode = \&decode_base64;

1;

__END__

=head1 NAME

MIME::Base64 - Encoding and decoding of base64 strings

=head1 DESCRIPTION

This is the PerlOnJava implementation of MIME::Base64. The actual implementation
is in the Java backend.

=head1 AUTHOR

Original module by Gisle Aas.

PerlOnJava implementation by Flavio S. Glock.

=head1 COPYRIGHT

Copyright 1995-1999, 2001-2004, 2010 Gisle Aas.

This library is free software; you can redistribute it and/or
modify it under the same terms as Perl itself.

=cut