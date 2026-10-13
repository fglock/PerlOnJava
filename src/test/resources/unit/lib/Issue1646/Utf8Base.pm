package Issue1646::Utf8Base;
use strict;
use warnings;
use utf8;
use feature ':5.16';

sub import {
    my ($class, $caller) = (shift, caller);
    return unless my @flags = @_;
    $_->import for qw(strict warnings utf8);
    feature->import(':5.16');
}

1;
