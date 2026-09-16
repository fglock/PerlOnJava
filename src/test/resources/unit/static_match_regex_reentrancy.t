use strict;
use warnings;
use Test::More tests => 1;

sub reentrant_static_global_match {
    my ($text, $reenter) = @_;
    my @seen;
    while ($text =~ /([a-z])(\d)/g) {
        my $outer = $1 . $2;
        my $inner = $reenter ? reentrant_static_global_match('z9', 0) : '';
        push @seen, $inner eq '' ? $outer : "$outer/$inner";
    }
    return join ',', @seen;
}

is(reentrant_static_global_match('a1b2', 1), 'a1/z9,b2/z9',
    'recursive reuse of one static /g call site preserves each target position');
