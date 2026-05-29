use strict;
use warnings;
use Test::More tests => 5;

{
    use warnings FATAL => 'uninitialized';
    use warnings 'uninitialized';

    ok(!warnings::fatal_enabled('uninitialized'),
        'normal use warnings clears inherited FATAL bit');
}

{
    use warnings FATAL => 'all';

    eval q{
        no warnings FATAL => 'all';
        use warnings;

        sub warnings_nonfatal_reset_probe {
            my $x;
            'a' . $x
        }
        1;
    } or die $@;

    ok(!warnings::fatal_enabled('uninitialized'),
        'no warnings FATAL followed by use warnings leaves category nonfatal');

    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };

    is(warnings_nonfatal_reset_probe(), 'a',
        'downgraded warning does not die');
    is(scalar @warnings, 1, 'downgraded warning is emitted once');
    like($warnings[0], qr/uninitialized/, 'captured downgraded warning');
}
