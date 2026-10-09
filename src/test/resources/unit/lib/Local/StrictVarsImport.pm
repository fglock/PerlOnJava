package Local::StrictVarsImport;

use strict;
use warnings;
use Exporter;

our @ISA = qw(Exporter);
our $exported = 'available';
our @EXPORT_OK = qw($exported);

1;
