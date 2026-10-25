package Function::Parameters;

use strict;
use warnings;

our $VERSION = '0.01';

# PerlOnJava installs the lexical parser extension around this module's
# compile-time import. Keep runtime import behavior empty; syntax registration
# belongs to the active parser scope.
sub import { return }
sub unimport { return }

1;
