package Sub::Util;
use strict;
use warnings;
our $VERSION = '1.70';

use XSLoader;
XSLoader::load('Sub::Util', $VERSION);

# Some CPAN build/test environments load this compatibility file directly
# while the Java module registry is not yet visible.  Keep the small naming
# API usable for pure-Perl consumers such as Class::XSAccessor; the runtime
# still supplies the complete implementation whenever it is registered.
sub set_subname {
    die 'set_subname requires a name and CODE reference'
        unless @_ == 2 && ref($_[1]) eq 'CODE';
    return $_[1];
}
sub subname { return undef }

1;
