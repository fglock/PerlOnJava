package Data::Dump::Filtered;

use strict;
use warnings;

use Carp ();
use Data::Dump ();
use Exporter qw(import);

our @EXPORT_OK = qw(add_dump_filter remove_dump_filter dump_filtered);

sub add_dump_filter {
    my ($filter) = @_;
    Carp::croak("add_dump_filter argument must be a code reference")
        unless ref($filter) eq 'CODE';
    push @Data::Dump::FILTERS, $filter;
    return $filter;
}

sub remove_dump_filter {
    my ($filter) = @_;
    @Data::Dump::FILTERS = grep { $_ ne $filter } @Data::Dump::FILTERS;
    return;
}

sub dump_filtered {
    my $filter = pop;
    Carp::croak("Last argument to dump_filtered must be undef or a code reference")
        if defined($filter) && ref($filter) ne 'CODE';
    local @Data::Dump::FILTERS = defined($filter) ? ($filter) : ();
    return Data::Dump::dump(@_);
}

1;
