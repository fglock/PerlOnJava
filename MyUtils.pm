
package MyUtils;
use strict;
use warnings;
our @EXPORT_OK = qw(func1 func2);
BEGIN { require Exporter; our @ISA = qw(Exporter) }
sub func1 { print "func1 called\n" }
sub func2 { print "func2 called\n" }
1;
