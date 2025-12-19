
package ModuleB;
use strict;
use warnings;
use lib '.';
use ModuleA qw(func2);
print "ModuleB loading\n";
func2();
print "ModuleB loaded\n";
1;
