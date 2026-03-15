package List::Util;
use strict;
use warnings;
our $VERSION = '1.63';

require Exporter;
our @ISA = qw(Exporter);

our @EXPORT_OK = qw(
    all any first min max minstr maxstr none notall
    product reduce reductions sum sum0 sample shuffle
    uniq uniqint uniqnum uniqstr
    zip zip_longest zip_shortest mesh mesh_longest mesh_shortest
    head tail pairs unpairs pairkeys pairvalues pairmap pairgrep pairfirst
);

use XSLoader;
XSLoader::load('List::Util', $VERSION);

1;
