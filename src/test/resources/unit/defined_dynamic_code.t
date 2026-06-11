use strict;
use warnings;
use Test::More;

sub Pending;

my $pending = 'main::Pending';
my $coderef = \&{$pending};

ok(defined($coderef), 'coderef scalar to declared-only sub is defined');
ok(!defined(&{$pending}), 'dynamic defined(&{name}) requires a defined CODE body');
ok(exists(&{$pending}), 'dynamic exists(&{name}) still sees the declared CODE slot');

{
    package ParentForDefinedDynamic;
    sub inherited { 'inherited' }

    package ChildForDefinedDynamic;
    our @ISA = ('ParentForDefinedDynamic');
}

my $inherited = 'ChildForDefinedDynamic::inherited';
ok(ChildForDefinedDynamic->can('inherited'), 'can() sees inherited methods');
ok(!defined(&{$inherited}), 'dynamic defined(&{name}) checks the stash slot, not inheritance');

{
    package ParentForMakeMakerDynamic;
    sub makemakerdflt_target { 'target' }

    package MYForMakeMakerDynamic;
    our @ISA = ('ParentForMakeMakerDynamic');
}

ok(MYForMakeMakerDynamic->can('makemakerdflt_target'), 'can() sees target fallback through inheritance');
ok(!defined(&{'MYForMakeMakerDynamic::makemakerdflt'}), 'missing MakeMaker section is not treated as defined');

done_testing();
