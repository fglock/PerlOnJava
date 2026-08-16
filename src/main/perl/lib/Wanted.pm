package Wanted;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '0.1.2';
our @EXPORT = qw(want);

# Wanted's XS implementation inspects the caller's requested return type.
# PerlOnJava already propagates scalar/list/void context through the call
# frame, but the CPAN module is often loaded before its XS registration is
# available.  The callers supported by the pure-Perl compatibility layer use
# OBJECT only as an optional null-object branch; scalar context is the safe
# default for that branch.
sub want {
    return 0;
}

1;
