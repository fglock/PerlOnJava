package Crypt::OpenSSL::Random;

use strict;
use warnings;

our $VERSION = '0.17';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    random_bytes
    random_pseudo_bytes
    random_seed
    random_status
    random_egd
);

use XSLoader;
XSLoader::load('Crypt::OpenSSL::Random', $VERSION);

1;
