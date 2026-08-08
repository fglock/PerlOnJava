package Crypt::Twofish2;

use XSLoader;

our $VERSION = '1.03';

XSLoader::load __PACKAGE__, $VERSION;

1;

__END__

=head1 NAME

Crypt::Twofish2 - Crypt::CBC compliant Twofish encryption module

=head1 DESCRIPTION

This is the PerlOnJava port of Marc Lehmann's Crypt::Twofish2 module.  It
preserves the module's XS API and uses Bouncy Castle's Twofish implementation.

=head1 AUTHOR

Marc Lehmann <schmorp@schmorp.de>

The original Twofish implementation was written by Doug Whiting.

=head1 COPYRIGHT AND LICENSE

This wrapper preserves the interface and documentation attribution of the
Crypt::Twofish2 1.03 distribution.  See the upstream distribution for its
copyright and licensing notes.

=cut
