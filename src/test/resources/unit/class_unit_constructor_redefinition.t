use strict;
use warnings;
use Test::More;
use feature 'class';
no warnings 'experimental::class';

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{
        class UnitConstructorRedefinition;
        field $value :reader = 42;
        1;
    };
}

is($@, '', 'unit class with a later field compiles');
is_deeply(\@warnings, [], 'replacing the synthetic constructor emits no redefinition warning');
done_testing;
