package YAML;

use strict;
use warnings;
use YAML::PP qw(Load Dump LoadFile DumpFile);
use Exporter 'import';

our @EXPORT = qw(Load Dump);
our @EXPORT_OK = qw(LoadFile DumpFile);
our $VERSION = '0.01';

1;
