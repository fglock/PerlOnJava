
package ComplexAPI;
use strict;
use warnings;
use lib '.';
use ComplexUtil qw(complex_func);

print "ComplexAPI loading\n";

my $res = complex_func("test");
print "ComplexAPI result: $res\n";

sub api_func {
    return complex_func("api");
}

1;
