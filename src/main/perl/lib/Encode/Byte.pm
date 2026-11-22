package Encode::Byte;

use strict;
use warnings;
use Encode ();

our $VERSION = '3.21';

# Encode::Byte's XS bootstrap registers single-byte charsets. Java Encode
# resolves those charsets through java.nio, so no native bootstrap is needed.

1;
