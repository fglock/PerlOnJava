
use lib '.';
use ModuleA qw(func1);
print "Main: calling func1\n";
func1();

print "Main: using ModuleB\n";
use ModuleB;
print "Main: done\n";
