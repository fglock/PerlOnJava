package Local::ImportStrictWarnings;

use strict;
use warnings;
no utf8;
use Import::Into;

sub import {
    my $caller = caller;
    strict->import::into($caller);
    warnings->import::into($caller);
}

1;
