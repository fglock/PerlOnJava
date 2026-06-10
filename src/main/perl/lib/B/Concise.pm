package B::Concise;

use strict;
use warnings;

our $VERSION = '1.88';

# PerlOnJava does not expose Perl op trees.  Loading B::Concise succeeds so
# probe-style tests can detect that compile() is unavailable and skip cleanly.

1;
