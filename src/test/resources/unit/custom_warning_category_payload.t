use strict;
use warnings;
use Test::More;

{
    package DateTime;
    use warnings::register;

    sub future_warning {
        warnings::warnif('far future DateTime payload');
    }
}

my @enabled;
{
    local $SIG{__WARN__} = sub { push @enabled, @_ };
    DateTime::future_warning();
}
is(scalar @enabled, 1, 'registered category inherits caller warnings');
like($enabled[0] // '', qr/^far future DateTime payload/,
    'registered-category warning retains its payload');

my @suppressed;
{
    no warnings 'DateTime';
    local $SIG{__WARN__} = sub { push @suppressed, @_ };
    DateTime::future_warning();
}
is_deeply(\@suppressed, [], 'no warnings suppresses the registered category');

done_testing;
