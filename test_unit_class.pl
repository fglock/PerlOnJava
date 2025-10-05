use v5.38;
use feature 'class';
no warnings 'experimental::class';

class TestUnit;
method m { return "unit-test" }

my $obj = TestUnit->new;
print "Object: ", ref($obj), "\n";
print "Method result: ", $obj->m, "\n";
