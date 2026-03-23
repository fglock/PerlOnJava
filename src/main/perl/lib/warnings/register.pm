package warnings::register;

use strict;

# Register the calling package as a custom warning category
sub import {
    my $package = caller;
    warnings::register_categories($package);
}

1;
