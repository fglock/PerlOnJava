package Template::Stash::XS;

# PerlOnJava: XS Stash is not available. Fall back to the pure Perl
# Template::Stash which provides identical functionality.

use strict;
use warnings;
use Template::Stash;

our @ISA = ('Template::Stash');

1;
