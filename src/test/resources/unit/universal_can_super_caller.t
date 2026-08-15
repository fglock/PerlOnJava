use strict;
use warnings;
use Test::More tests => 3;

{
    package CanSuper::Base;
    sub import { 'base import' }

    package CanSuper::Middle;
    our @ISA = ('CanSuper::Base');
    sub import { 'middle import' }
    sub inherited_import {
        my ($class) = @_;
        return $class->can('SUPER::import');
    }

    package CanSuper::Child;
    our @ISA = ('CanSuper::Middle');
}

my $super_import = CanSuper::Child->inherited_import;
ok $super_import, 'can finds a SUPER method relative to the calling package';
is $super_import->(), 'base import', 'can(SUPER::method) skips the caller package';
is(CanSuper::Child->can('CanSuper::Middle::SUPER::import')->(),
  'base import', 'explicit Package::SUPER::method has the same resolution');
