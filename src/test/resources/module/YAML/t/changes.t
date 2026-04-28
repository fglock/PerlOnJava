use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 1;

SKIP: {
    skip("Can't parse Changes file yet :(", 1);
}

# my @values = LoadFile("Changes");
