package PerlIO;

use strict;
use warnings;

our $VERSION = '1.12';

sub get_layers {
    return wantarray ? () : 0;
}

1;
