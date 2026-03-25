package open;

# Stub for open pragma - sets default I/O layers
# PerlOnJava implementation by Flavio S. Glock

use strict;
use warnings;

our $VERSION = '1.14';

# The open pragma sets default PerlIO layers for input/output
# In PerlOnJava, UTF-8 is the default encoding

sub import {
    my $class = shift;
    # For now, accept but ignore layer specifications
    # PerlOnJava defaults to UTF-8 encoding
}

1;
