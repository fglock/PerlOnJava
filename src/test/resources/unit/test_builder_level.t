use strict;
use warnings;
use Test::More tests => 1;
use Test2::API qw/test2_list_context_acquire_callbacks/;

my @callbacks = test2_list_context_acquire_callbacks();
my %params = (level => 2);

{
    local $Test::Builder::Level = $Test::Builder::Level + 1;
    $callbacks[0]->(\%params);
}

is($params{level}, 3, 'Test::Builder context callback honors localized level');
