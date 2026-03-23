package Encode;
use strict;
use warnings;
our $VERSION = '3.21';

require Exporter;
our @ISA = qw(Exporter);
our @EXPORT = qw(decode encode encode_utf8 decode_utf8 find_encoding);
our @EXPORT_OK = qw(
    _utf8_off _utf8_on define_encoding from_to is_utf8
    perlio_ok resolve_alias
    encodings
    FB_DEFAULT FB_CROAK FB_QUIET FB_WARN FB_HTMLCREF FB_XMLCREF
    LEAVE_SRC
);

use XSLoader;
XSLoader::load('Encode', $VERSION);

1;
