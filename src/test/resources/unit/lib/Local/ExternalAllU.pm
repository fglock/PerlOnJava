package Local::ExternalAllU;

use strict;
use warnings;
use Exporter qw(import);

our @EXPORT_OK = qw(all_u);

sub all_u (&@) {
    my $predicate = shift;
    return undef unless @_;
    $predicate->() or return 0 foreach @_;
    return 1;
}

1;
