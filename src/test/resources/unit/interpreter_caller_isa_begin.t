package Type::Library;

package CallerIsaBegin;
our $result;

sub check_parent {
    return caller->isa('Type::Library') ? 1 : 0;
}

BEGIN {
    @ISA = ('Type::Library');
    $result = check_parent();
}

package main;
use Test::More tests => 1;
ok($CallerIsaBegin::result, 'caller package inherits its parent inside BEGIN');
