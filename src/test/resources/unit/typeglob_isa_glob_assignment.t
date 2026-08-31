use strict;
use warnings;
use Test::More;

{
    package TypeglobIsaGlobAssignment;
    our @ISA = ('TypeglobIsaGlobAssignmentBase');
}

ok(TypeglobIsaGlobAssignment->isa('TypeglobIsaGlobAssignmentBase'),
   'inheritance exists before glob assignment');

undef *TypeglobIsaGlobAssignmentEmpty;
*TypeglobIsaGlobAssignment::ISA = *TypeglobIsaGlobAssignmentEmpty;

ok(!TypeglobIsaGlobAssignment->isa('TypeglobIsaGlobAssignmentBase'),
   'glob assignment to an empty source removes inherited classes');

done_testing;
