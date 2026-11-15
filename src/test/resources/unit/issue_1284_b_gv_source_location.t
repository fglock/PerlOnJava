use strict;
use warnings;
use Test::More;
use B;

package BGVSourceLocation::Parent;
our $method_line = __LINE__ + 1;
sub inherited_method { return 1 }

package BGVSourceLocation::Child;
our @ISA = ('BGVSourceLocation::Parent');

package main;
our $local_line = __LINE__ + 1;
sub local_method { return 2 }

my $anonymous_line = __LINE__ + 1;
my $anonymous_method = sub { return 3 };

my $local_gv = B::svref_2object(\&local_method)->GV;
is($local_gv->FILE, __FILE__, 'B::GV::FILE reports a local sub source file');
is($local_gv->LINE, $local_line, 'B::GV::LINE reports a local sub source line');

my $inherited = BGVSourceLocation::Child->can('inherited_method');
my $inherited_gv = B::svref_2object($inherited)->GV;
is($inherited_gv->FILE, __FILE__, 'B::GV::FILE reports an inherited sub source file');
is($inherited_gv->LINE, $BGVSourceLocation::Parent::method_line,
    'B::GV::LINE reports the inherited sub definition line');

my $anonymous_gv = B::svref_2object($anonymous_method)->GV;
is($anonymous_gv->FILE, __FILE__, 'B::GV::FILE reports an anonymous sub source file');
is($anonymous_gv->LINE, $anonymous_line, 'B::GV::LINE reports an anonymous sub source line');

done_testing;
