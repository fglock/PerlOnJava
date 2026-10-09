use strict;
use warnings;
no warnings 'once';
use Test::More;

# `strict vars` treats a package scalar as imported when a scalar reference is assigned
# to its glob from a different package. An alias assignment made inside the same package
# does not import the name.

package Glob::Owner;
our $UNRELATED = 'declared only';

package Glob::Holder;
sub import_scalar {
    my ($globname, $ref) = @_;
    no strict 'refs';
    *$globname = $ref;
}
sub alias_in_own_package {
    my ($globname, $ref) = @_;
    no strict 'refs';
    my $newglob = \*$globname;
    local *alias = *$newglob;
    *alias = $ref;
}

package main;

Glob::Holder::import_scalar('Glob::Owner::IMPORTED', \'imported value');
my $imported = eval 'package Glob::Owner; my $seen = $IMPORTED; 1';
ok $imported, 'a scalar assigned to the glob from another package is visible under strict';
is $Glob::Owner::IMPORTED, 'imported value', 'the imported scalar is the package variable';

Glob::Holder::alias_in_own_package('Glob::Owner::ALIASED', \'aliased value');
my $aliased = eval 'package Glob::Owner; my $seen = $ALIASED; 1';
ok !$aliased, 'an alias assigned inside its own package does not import the name';

my $control = eval 'package Glob::Owner; my $seen = $NEVER_IMPORTED; 1';
ok !$control, 'a name that was never imported stays invisible under strict';

done_testing;
