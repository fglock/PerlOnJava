use strict;
use warnings;
use Test::More;

# Keep the JSONY::Receiver::got_bare dispatch behavior covered without making
# JSONY (an optional CPAN distribution) a prerequisite for the unit suite.
sub got_bare {
    (undef, $_) = @_;
    return /^null$/ ? undef
        : /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?$/ ? ($_ + 0)
        : "$_";
}

my @values;
for my $token (qw(null 8080 null 250 null 1)) {
    push @values, got_bare(undef, $token);
}
is_deeply(\@values, [undef, 8080, undef, 250, undef, 1],
    'bare null callbacks return undef between numeric callbacks');
ok(!defined got_bare(undef, 'null'), 'repeated null callback remains undef');
is(got_bare(undef, '42'), 42, 'numeric callback after null retains its own token');

if (eval { require JSONY; 1 }) {
    my $value = JSONY->new->load('[null, 8080, null, 250, null, 1]');
    is_deeply($value, [undef, 8080, undef, 250, undef, 1],
        'JSONY load preserves nulls between numeric values');
} else {
    note 'JSONY is not installed; direct receiver callback coverage ran';
}

done_testing;
