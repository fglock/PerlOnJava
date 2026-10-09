use strict;
use Test::More;

# This file has no warnings pragma, so an escaping `last` follows $^W, as in
# Perl: quiet by default, and reported once $^W is set at runtime.
sub escaping_last { last }

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    for (1) { escaping_last() }
}
is_deeply \@warnings, [], 'no warnings pragma and no $^W: escaping last is quiet';

@warnings = ();
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    local $^W = 1;
    for (1) { escaping_last() }
}
is scalar(@warnings), 1, 'no warnings pragma with $^W set: escaping last warns';

done_testing;
