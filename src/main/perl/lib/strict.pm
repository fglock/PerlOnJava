package strict;
our $VERSION = '1.14';

#
# Original strict pragma is part of the Perl core, maintained by the Perl 5 Porters.
#
# PerlOnJava implementation by Flavio S. Glock.
# The XS implementation is in: src/main/java/org/perlonjava/perlmodule/Strict.java
#

use XSLoader;
XSLoader::load( 'Strict', $VERSION );

1;
