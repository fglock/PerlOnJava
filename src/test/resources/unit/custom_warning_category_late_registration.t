use strict;
use warnings;
use Test::More;

my $loaded = eval q{
    package LateWarningCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('late registered category payload');
    }

    1;
};
ok($loaded, 'registered a warning category in a later compilation');

my @enabled;
{
    local $SIG{__WARN__} = sub { push @enabled, @_ };
    LateWarningCategory::emit_warning();
}
is(scalar @enabled, 1, 'earlier use warnings includes a later category');
like($enabled[0] // '', qr/^late registered category payload/,
    'late registered warning retains its payload');

my @suppressed;
my $suppressed = eval q{
    no warnings 'LateWarningCategory';
    local $SIG{__WARN__} = sub { push @suppressed, @_ };
    LateWarningCategory::emit_warning();
    1;
};
ok($suppressed, 'compiled a later explicit category suppression');
is_deeply(\@suppressed, [], 'explicit suppression remains authoritative');

done_testing;
