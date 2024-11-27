package JSON;

use warnings;
use strict;

# NOTE: The rest of the code is in file:
#       src/main/java/org/perlonjava/perlmodule/Json.java

sub new {
    my ($class) = @_;
    return bless {}, $class;
}

1;

