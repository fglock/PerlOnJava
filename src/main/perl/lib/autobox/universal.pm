package autobox::universal;

use strict;
use warnings;

use autobox (); # don't import
use Exporter (); # Perl 5.8 compatible import setup
use XSLoader;

our @ISA = qw(Exporter);
our @EXPORT_OK = qw(type);

XSLoader::load('autobox::universal');

1;
