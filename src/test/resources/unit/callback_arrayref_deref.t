use strict;
use warnings;
use Test::More;

sub trim_row {
    splice @{$_[1]}, 1;
}

my @row = qw(alpha beta gamma);
trim_row(undef, \@row);

is_deeply(\@row, ['alpha'], 'array reference in callback arguments dereferences for splice');

done_testing;
