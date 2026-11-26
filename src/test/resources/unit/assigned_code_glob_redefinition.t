use strict;
use warnings;
use Test::More tests => 10;

# A glob that received a CODE reference by assignment counts as already
# defined: a later `sub NAME {...}` for that glob warns and installs a new CV,
# even after `undef` emptied the old one. Assigning the glob's own CV back
# (as `use subs` does) and plain declarations do not have that effect.

no warnings 'once';
my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

my $code = sub { 'old' };
*main::assigned = $code;
undef &$code;
eval 'use warnings; sub assigned { "new" } 1' or die $@;
ok(!defined &$code, 'the emptied CV stays empty');
ok(\&main::assigned != $code, 'the glob receives a new CV');
is(main::assigned(), 'new', 'the new CV runs');
like(shift(@warnings) // '', qr/^Subroutine assigned redefined/, 'the definition warns');

my $again = \&main::assigned;
undef &main::assigned;
eval 'use warnings; sub assigned { "newer" } 1' or die $@;
ok(\&main::assigned != $again, 'the glob stays assumed after a definition');
like(shift(@warnings) // '', qr/^Subroutine assigned redefined/, 'the second definition warns');

sub named_first { 1 }
my $named = \&named_first;
undef &named_first;
eval 'use warnings; sub named_first { 2 } 1' or die $@;
ok(\&named_first == $named, 'a sub defined by name is redefined in place');

use subs qw(predeclared);
my $predeclared = \&predeclared;
eval 'use warnings; sub predeclared { 3 } 1' or die $@;
ok(\&predeclared == $predeclared, 'use subs keeps the predeclared CV');
is(predeclared(), 3, 'the predeclared CV runs the definition');

is(scalar @warnings, 0, 'no other warnings');
