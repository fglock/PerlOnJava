package DynaLoader;

# placeholder module for Perl DynaLoader

use Exporter "import";
use warnings;
use strict;
use Symbol;

our @EXPORT = ("bootstrap");

sub bootstrap {
    die "DynaLoader::bootstrap not implemented\n";
}

# Perl tests use this:
#    return !defined &DynaLoader::boot_DynaLoader;
sub boot_DynaLoader {
    # placeholder
}

undef &boot_DynaLoader;

1;
