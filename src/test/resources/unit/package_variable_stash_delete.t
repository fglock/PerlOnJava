use strict;
use warnings;
use Test::More;

# Compiled code is bound to the glob it was compiled against. Deleting the stash
# entry leaves that binding intact, while a string eval of the name resolves
# through the stash again.
package Binding::Probe;
our $X = 'q';
sub direct   { return defined $X ? $X : 'undef' }
sub via_eval { my $value = eval '$X'; return defined $value ? $value : 'undef' }

package main;

is Binding::Probe::direct(), 'q', 'compiled read sees the package scalar';
is Binding::Probe::via_eval(), 'q', 'string eval sees the package scalar';

delete $Binding::Probe::{X};

is Binding::Probe::direct(), 'q',
    'compiled read keeps its glob after the stash entry is deleted';
is Binding::Probe::via_eval(), 'undef',
    'string eval after delete resolves to a new empty scalar';

{
    no strict 'refs';
    *{'Binding::Probe::X'} = \'reinstalled';
}

is Binding::Probe::direct(), 'q',
    'compiled read still uses its original glob after reinstall';
is Binding::Probe::via_eval(), 'reinstalled',
    'string eval after reinstall sees the new scalar';

done_testing;
