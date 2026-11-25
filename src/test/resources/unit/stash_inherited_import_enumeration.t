use strict;
use warnings;
use Test::More;

BEGIN { $INC{'ChildForStashImport.pm'} = __FILE__ }

{
    package ParentForStashImport;
    sub import { 'original' }

    package ChildForStashImport;
    our @ISA = ('ParentForStashImport');
}

use ChildForStashImport;
my @keys;
{
    no strict 'refs';
    @keys = keys %ChildForStashImport::;
    ok(exists $ChildForStashImport::{import}, 'inherited import is visible in the child stash');
}
ok(grep($_ eq 'import', @keys), 'stash enumeration includes inherited import');
{
    no warnings 'redefine';
    *ParentForStashImport::import = sub { 'updated' };
}
is(ChildForStashImport->import(), 'updated',
    'stash enumeration does not detach inherited method lookup');

done_testing();
