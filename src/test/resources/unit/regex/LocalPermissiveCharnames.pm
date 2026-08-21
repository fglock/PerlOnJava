package LocalPermissiveCharnames;

use strict;
use warnings;

sub translator {
    return $_[0];
}

sub import {
    $^H{charnames} = \&translator;
}

1;
