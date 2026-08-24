use strict;
use warnings;
use Test::More;

{
    package ExternalCategory;
    use warnings::register;

    sub emit_warning {
        warnings::warnif('external category payload');
    }
}

{
    package RegisteredBridge;
    use warnings::register;
    no warnings 'ExternalCategory';

    sub relay_warning {
        ExternalCategory::emit_warning();
    }
}

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    RegisteredBridge::relay_warning();
}
is_deeply(\@warnings, [],
    'intervening registered-package suppression remains authoritative');

done_testing;
