use strict;
use warnings;
use Test::More;

# Test::More::skip() leaves its SKIP block with a non-local `last`.  Perl
# reports "Exiting subroutine via last" from the warnings in effect at that
# `last` statement, so Test::More's own `no warnings 'exiting'` must suppress
# the warning even though this file has `use warnings`.
my @warnings;
my $after_skip;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    SKIP: {
        skip 'intentional skip', 1;
        $after_skip = 1;
    }
}
ok !$after_skip, 'skip() leaves the SKIP block';
is_deeply \@warnings, [], 'skip() emits no exiting warning';

# A helper that disables the warning lexically is quiet; the caller's
# `use warnings` does not re-enable it.
sub quiet_last_skip { no warnings 'exiting'; last SKIP }

@warnings = ();
my $after_helper;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    SKIP: {
        quiet_last_skip();
        $after_helper = 1;
    }
}
ok !$after_helper, 'last SKIP from a helper leaves the SKIP block';
is_deeply \@warnings, [], 'helper with no warnings exiting stays quiet';

# A helper that keeps the warning enabled still reports the escaping last.
sub loud_last { last }

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    for (1) { loud_last() }
}
is scalar(@warnings), 1, 'helper with exiting enabled still warns';
like $warnings[0], qr/\AExiting subroutine via last at \S+ line \d+/,
    'warning names the escaping last';

done_testing;
