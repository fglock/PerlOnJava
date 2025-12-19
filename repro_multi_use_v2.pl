
use lib '.';
use MyUtils qw(func1);
print "Called func1\n";
func1();

package Other;
use lib '.';
use MyUtils qw(func2);
print "Calling func2 from Other\n";
func2();
print "Done\n";
