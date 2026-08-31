use strict;
use warnings;
use Test::More;

{
    package TypeglobCompiledCallPin;
    no warnings 'once';
    *run = sub { 'pinned CV' };
}

is(TypeglobCompiledCallPin->run, 'pinned CV', 'method call sees installed CODE slot');
is(&TypeglobCompiledCallPin::run, 'pinned CV', 'direct call is compiled against CODE slot');

delete $TypeglobCompiledCallPin::{run};

ok(!TypeglobCompiledCallPin->can('run'), 'stash deletion removes method lookup');
is(&TypeglobCompiledCallPin::run, 'pinned CV',
   'direct call compiled before stash deletion keeps its CV');

done_testing;
