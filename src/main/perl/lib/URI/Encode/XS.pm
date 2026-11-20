package URI::Encode::XS;

use strict;
use warnings;
use Exporter 'import';

our $VERSION = '0.11';
our @EXPORT_OK = qw(uri_encode uri_encode_utf8 uri_decode uri_decode_utf8);

use XSLoader;
XSLoader::load('URI::Encode::XS', $VERSION);

1;
