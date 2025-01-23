# MyModule.pm
package MyModule;

use strict;
use warnings;
use Exporter;

our @EXPORT    = qw(sub1);        # Automatically exported
our @EXPORT_OK = qw(sub2 sub3);   # Must be requested explicitly

sub sub1 { return "sub1 called"; }
sub sub2 { return "sub2 called"; }
sub sub3 { return "sub3 called"; }

1;

# script.pl
use MyModule qw(sub2);   # Imports sub2 but not sub1 by default

print sub2();            # Prints "sub2 called"

