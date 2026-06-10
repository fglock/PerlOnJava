package Digest::MD2;

use strict;
use warnings;
use base "Digest::base";

our $VERSION = '2.04';

use Exporter ();
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(md2 md2_hex md2_base64);

require XSLoader;
XSLoader::load('Digest::MD2', $VERSION);

*reset = \&new;

1;
