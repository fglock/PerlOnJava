package namespace::clean;

use strict;
use warnings;

# Keep the unit-test regression independent of a host-installed CPAN copy.
# The focused unit tests only need the default behavior for CODE entries
# present before this pragma is used.
our @CLEAN_AT_CHECK;

sub import {
    my ($pragma, @args) = @_;
    die "unit-test namespace::clean shim only supports default import\n" if @args;

    my $caller = caller;
    no strict 'refs';
    my @names = grep { defined *{"${caller}::$_"}{CODE} } keys %{"${caller}::"};
    push @CLEAN_AT_CHECK, [$caller, @names];
}

CHECK {
    no strict 'refs';
    for my $entry (@CLEAN_AT_CHECK) {
        my ($caller, @names) = @$entry;
        delete ${"${caller}::"}{$_} for @names;
    }
}

1;
