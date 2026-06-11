package Class::Load::XS;

use strict;
use warnings;

our $VERSION = '0.10';

use Class::Load::PP ();

sub is_class_loaded {
    goto &Class::Load::PP::is_class_loaded;
}

1;
