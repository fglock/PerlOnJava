use strict;
use warnings;
use Test::More;

sub typeglob_assignment_source { 'value' }

my $assignment_result;
{
    no strict 'refs';
    $assignment_result = *{'TypeglobAssignmentTarget::installed'} =
        \&typeglob_assignment_source;
}
is(ref(\$assignment_result), 'GLOB',
    'non-void typeglob assignment returns its glob value');

{
    package TypeglobSelectionTarget;
    open TypeglobSelectionTarget::handle, '<', 'Makefile' or die "open Makefile: $!";
}
select TypeglobSelectionTarget::handle;
my $selected_glob = \*TypeglobSelectionTarget::handle;
my $selected_stash = \%TypeglobSelectionTarget::;
{
    no strict 'refs';
    *TypeglobSelectionTarget:: = *TypeglobSelectionSource::;
}
is(select(), $selected_glob,
    'select retains the selected glob after its stash entry is detached');

done_testing();
