package YAML;

use strict;
use warnings;
use YAML::PP qw(Load Dump LoadFile DumpFile);
use Exporter 'import';

our @EXPORT = qw(Load Dump);
our @EXPORT_OK = qw(LoadFile DumpFile freeze thaw);
our $VERSION = '1.31';  # Match CPAN YAML version; we wrap YAML::PP

# Storable-compatible aliases used by POE::Filter::Reference
*freeze = \&Dump;
*thaw   = \&Load;

1;
