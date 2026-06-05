package Crypt::OpenSSL::Random;

use strict;
use warnings;

our $VERSION = '0.17';

use XSLoader;
XSLoader::load('Crypt::OpenSSL::Random', $VERSION);

1;
