use strict;
use warnings;
use feature 'switch';
use Test::More;

my @inclusive;
for my $value (qw(a b c d)) {
    given ($value) {
        when ($_ eq 'b' .. $_ eq 'c') { push @inclusive, $value }
    }
}
is_deeply \@inclusive, [qw(b c)], 'inclusive range when condition is boolean';

my @exclusive;
for my $value (qw(a b c d)) {
    given ($value) {
        when ($_ eq 'b' ... $_ eq 'c') { push @exclusive, $value }
    }
}
is_deeply \@exclusive, [qw(b c)], 'exclusive range when condition is boolean';

done_testing;
