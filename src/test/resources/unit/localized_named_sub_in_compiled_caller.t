use strict;
use warnings;

use Test::More tests => 3;

{
    package LocalizedProvider;
    sub value { 'original' }

    package LocalizedCaller;
    sub call_value { LocalizedProvider::value() }
}

is(LocalizedCaller::call_value(), 'original',
    'compiled caller initially uses the original named sub');
{
    no warnings 'redefine';
    local *LocalizedProvider::value = sub { 'localized' };
    is(LocalizedCaller::call_value(), 'localized',
        'compiled caller observes a localized named sub');
}
is(LocalizedCaller::call_value(), 'original',
    'compiled caller observes the restored named sub');
