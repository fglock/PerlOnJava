package PerlIO::scalar;
use strict;
use warnings;

our $VERSION = '0.01';

# PerlOnJava implements scalar-backed filehandles in the runtime.  This module
# exists so code that preloads PerlIO::scalar, as it would on perl5, succeeds.

1;
