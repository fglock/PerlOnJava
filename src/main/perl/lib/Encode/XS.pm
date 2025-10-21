package Encode::XS;
use strict;
use warnings;

# Load the Java XS implementation
use XSLoader;
XSLoader::load('Encode::XS');

# The decode, encode, and name methods are provided by the Java implementation
# in org.perlonjava.perlmodule.EncodeXS

1;

