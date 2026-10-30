package Devel::Size;

use strict;
use warnings;
require Exporter;
our @ISA = qw(Exporter);
our $VERSION = '0.87';
our @EXPORT_OK = qw(size total_size);
our %EXPORT_TAGS = (all => \@EXPORT_OK);

# Retained for source compatibility. JOL can inspect the JVM graph directly,
# so native XS warnings about incomplete CVs and dangling C pointers do not
# apply; these variables do not change JOL's behavior. The estimate includes
# reachable PerlOnJava implementation objects.
our $warn = 1;
our $dangle = 0;

require XSLoader;
XSLoader::load(__PACKAGE__, $VERSION);

1;
