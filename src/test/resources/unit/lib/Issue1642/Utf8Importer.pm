package Issue1642::Utf8Importer;

use strict;
use warnings;
use utf8;
use feature ':5.16';

sub import {
    # Mirror Mojo::Base -strict, which imports these pragmas into its caller.
    my ($class, $caller) = (shift, caller);
    return unless my @flags = @_;
    $_->import for qw(strict warnings utf8);
    feature->import(':5.16');
}

1;
