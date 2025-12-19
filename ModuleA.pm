
package ModuleA;
use strict;
use warnings;
our @EXPORT_OK = qw(func1 func2);
BEGIN { require Exporter; our @ISA = qw(Exporter) }
sub func1 { print "ModuleA::func1 called\n" }
sub func2 { print "ModuleA::func2 called\n" }
1;
