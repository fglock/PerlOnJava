use strict;
use warnings;
use Test::More tests => 3;

our $slot;

# Keep the definition dynamic to reproduce perl5_t/op/sub_lval.t: the direct
# call below is unresolved while it is parsed and must remain eligible for
# AUTOLOAD dispatch.
eval 'sub AUTOLOAD : lvalue { $slot }';
is($@, '', 'installed lvalue AUTOLOAD dynamically');

missing_lvalue_sub() = 12;
is($slot, 12, 'undefined direct call can assign through lvalue AUTOLOAD');

my $compiled = eval 'sub declared_non_lvalue; declared_non_lvalue() = 1; 1';
ok(!$compiled && $@ =~ /Can't modify non-lvalue subroutine call/,
    'forward-declared non-lvalue sub still fails during compilation');
