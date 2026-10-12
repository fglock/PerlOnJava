use strict;
use warnings;
use Test::More;

sub numeric_channel {
    my ($value) = @_;
    no warnings 'numeric';
    return (!utf8::is_utf8($value)
        && length((my $dummy = '') & $value)
        && 0 + $value eq $value && $value * 0 == 0) ? 1 : 0;
}

is(numeric_channel(1), 1, 'literal number has numeric channel');
is(numeric_channel('1'), 0, 'literal string keeps string channel');
my $true_zero = 1 + '0 but true';
ok(!utf8::is_utf8($true_zero), 'true-zero arithmetic result is not UTF-8 flagged');
is(numeric_channel($true_zero), 1, 'arithmetic with true zero yields numeric channel');
{
    no warnings 'numeric';
    my $nonnumeric = 23 + 'bar';
    ok(!utf8::is_utf8($nonnumeric), 'nonnumeric arithmetic result is not UTF-8 flagged');
    is(numeric_channel($nonnumeric), 1,
        'arithmetic with nonnumeric string yields numeric channel');
}
my $reversed = '0 but true' + 1;
ok(!utf8::is_utf8($reversed), 'reversed arithmetic result is not UTF-8 flagged');
is(numeric_channel($reversed), 1, 'reversed arithmetic yields numeric channel');

done_testing();
