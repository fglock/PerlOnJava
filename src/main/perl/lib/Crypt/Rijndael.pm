package Crypt::Rijndael;

use strict;
use warnings;

our $VERSION = '1.16';

use XSLoader;
XSLoader::load('Crypt::Rijndael', $VERSION);

1;

__END__

=head1 NAME

Crypt::Rijndael - Crypt::CBC compliant Rijndael encryption module

=head1 DESCRIPTION

This PerlOnJava port preserves the Crypt::Rijndael 1.16 XS API and uses the
Java Cryptography Architecture AES provider. It supports AES's 128-bit block
size and 128-, 192-, and 256-bit keys in ECB, CBC, CFB128, OFB, and CTR modes.
PCBC remains unsupported, matching the upstream distribution.

=head1 AUTHOR

Currently maintained by Leon Timmermans E<lt>leont@cpan.orgE<gt>.

Previously maintained by brian d foy. Original code by Rafael R. Sevilla.

=head1 LICENSE

This wrapper preserves the interface and attribution of Crypt::Rijndael 1.16,
which is licensed under the Lesser GNU Public License v3 or later.

=cut
