use strict;
use warnings;
use Test::More;

{
    package RecursiveCategory;
    use warnings::register;

    sub recurse {
        my ($depth) = @_;
        return recurse($depth - 1) if $depth;
        warnings::warnif('recursive category payload');
        return;
    }
}

my @enabled;
{
    local $SIG{__WARN__} = sub { push @enabled, @_ };
    RecursiveCategory::recurse(4);
}
is(scalar @enabled, 1, 'recursive registered frames reach the external caller');
like($enabled[0] // '', qr/^recursive category payload/,
    'recursive warning retains its payload');

my @suppressed;
{
    no warnings 'RecursiveCategory';
    local $SIG{__WARN__} = sub { push @suppressed, @_ };
    RecursiveCategory::recurse(4);
}
is_deeply(\@suppressed, [], 'external suppression survives recursive registered frames');

done_testing;
