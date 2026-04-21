package List::Util;
use strict;
use warnings;

our $VERSION    = '1.70';
our $XS_VERSION = $VERSION;
$VERSION =~ tr/_//d;

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
XSLoader::load('List::Util', $XS_VERSION);

# Used by shuffle()
our $RAND;

sub import
{
    my $pkg = caller;

    # (RT88848) Touch the caller's $a and $b, to avoid the "Name used only
    # once: possible typo" warning.
    no strict 'refs';
    ${"${pkg}::a"} = ${"${pkg}::a"};
    ${"${pkg}::b"} = ${"${pkg}::b"};

    goto &Exporter::import;
}

# For objects returned by pairs()
sub List::Util::_Pair::key     { shift->[0] }
sub List::Util::_Pair::value   { shift->[1] }
sub List::Util::_Pair::TO_JSON { [ @{+shift} ] }

1;
