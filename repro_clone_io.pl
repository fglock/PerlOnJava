
use Test2::Util qw(clone_io);
print "Loaded Test2::Util\n";

if (defined &clone_io) {
    print "clone_io is defined\n";
} else {
    print "clone_io is NOT defined\n";
}

package MyTest;
use Test2::Util qw(clone_io);
if (defined &clone_io) {
    print "MyTest::clone_io is defined\n";
} else {
    print "MyTest::clone_io is NOT defined\n";
}
