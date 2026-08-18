use strict;
use warnings;
use Test::More tests => 4;

{
    package OurPackageFirst;
    our $slot = 'first';

    package OurPackageSecond;
    our $slot = 'second';

    ::is($slot, 'second',
        'redeclared our scalar reads from its new package');
    {
        local $slot = 'localized second';
        ::is($slot, 'localized second',
            'local uses the redeclared our package');
    }
    ::is($slot, 'second',
        'redeclared our scalar restores its package value');
    ::is($OurPackageFirst::slot, 'first',
        'the earlier package scalar remains independent');
}
