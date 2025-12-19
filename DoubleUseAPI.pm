
package DoubleUseAPI;
use strict;
use warnings;
use DoubleUseUtil qw(func1);

print "First use done\n";
func1();

use DoubleUseUtil qw(func2);
print "Second use done\n";
func2();

sub my_sub { print "my_sub\n" }
my_sub();

1;
