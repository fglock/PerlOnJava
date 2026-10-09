use strict;
use warnings;
use Test::More tests => 2;

use CPAN;
require CPAN::Module;

my $module = bless { ID => 'Regression::Module' }, 'CPAN::Module';
local $CPAN::META = {
    readwrite => { 'CPAN::Module' => { 'Regression::Module' => $module } },
    readonly => {},
};
local $CPAN::Config_loaded = 1;
{
    no warnings 'redefine';
    local *CPAN::Index::reload = sub { };
    my @objects = CPAN->all_objects('CPAN::Module');
    is(scalar @objects, 1, 'all_objects returns every mutable metadata object');
    is($objects[0], $module, 'all_objects returns the stored module object');
}
