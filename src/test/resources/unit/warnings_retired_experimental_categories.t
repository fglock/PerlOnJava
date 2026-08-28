use strict;
use warnings;
use feature 'isa';
use Test::More tests => 2;

{
    no warnings 'experimental::isa';
    my $obj = bless {}, 'WarningCategoryObject';
    ok($obj isa WarningCategoryObject,
        'retired experimental::isa category remains accepted');
}

{
    no warnings 'experimental::alpha_assertions';
    pass('retired experimental::alpha_assertions category remains accepted');
}
