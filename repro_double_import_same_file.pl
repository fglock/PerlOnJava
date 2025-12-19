
package MyUtil;
use strict;
use warnings;
our @EXPORT_OK = qw(func1 func2);
BEGIN { require Exporter; our @ISA = qw(Exporter) }
sub func1 { "func1" }
sub func2 { "func2" }
1;

package MyConsumer;
use strict;
use warnings;
use lib '.';
use MyUtil qw(func1);

print "After first import: func1 is " . (defined &func1 ? "defined" : "undefined") . "\n";
print "After first import: func2 is " . (defined &func2 ? "defined" : "undefined") . "\n";

use MyUtil qw(func2);

print "After second import: func1 is " . (defined &func1 ? "defined" : "undefined") . "\n";
print "After second import: func2 is " . (defined &func2 ? "defined" : "undefined") . "\n";

1;

package main;
use lib '.';
use MyConsumer;
