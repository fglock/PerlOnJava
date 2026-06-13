package Data::UUID;

use strict;
use warnings;

our $VERSION = '1.226';

use Exporter ();
our @ISA = qw(Exporter);
our @EXPORT_OK = qw(
    NameSpace_DNS
    NameSpace_URL
    NameSpace_OID
    NameSpace_X500
);

use XSLoader;
XSLoader::load('Data::UUID');

1;
