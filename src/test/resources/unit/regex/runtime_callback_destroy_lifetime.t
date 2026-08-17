use strict;
use warnings;
use Test::More tests => 3;

{
    package RuntimeCallbackDestroyLifetime;
    our $destroyed = 0;
    sub DESTROY { $destroyed++ }
}

{
    my $r1;
    {
        my $value = bless [1], 'RuntimeCallbackDestroyLifetime';
        $r1 = eval 'qr/(??{$value->[0]})/';
    }
    my $r2 = eval 'qr/a$r1/';
    my $value = 2;
    ok(eval '"a1" =~ qr/^$r2$/', 'runtime callback remains executable');
    "a" =~ /a(?{1})/;
    is($RuntimeCallbackDestroyLifetime::destroyed, 0,
        'captured callback value remains alive in the enclosing scope');
}

is($RuntimeCallbackDestroyLifetime::destroyed, 1,
    'captured callback value is released after scope exit');
