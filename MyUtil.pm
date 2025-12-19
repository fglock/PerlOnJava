
package MyUtil;
use strict;
use warnings;
our @EXPORT_OK = qw(func1 func2);
BEGIN { require Exporter; our @ISA = qw(Exporter) }
sub func1 { "func1" }
sub func2 { "func2" }
1;
