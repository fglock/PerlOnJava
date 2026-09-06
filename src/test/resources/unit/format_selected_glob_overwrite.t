use strict;
use warnings;
use Test::More tests => 18;

my $stdout = *STDOUT;
select $stdout;
$stdout = 1;

# `perl5_t/t/test.pl` emits each assertion through an explicit `print STDOUT`
# while this detached IO is selected.  That must not invalidate the selected
# handle used by the following format write.
ok(print(STDOUT ''), 'explicit STDOUT print preserves the selected handle');
ok(select() ne '', 'select still returns the detached selected handle');

our $value = 'format value';
format STDOUT =
@ @<<<<<<<<<<
"#", $value
.

ok write, 'write through selected handle after glob holder overwrite';

sub argument_value { my ($value) = @_; return $value }
sub argument_truth { my ($value) = @_; return $value ? 1 : 0 }
sub prototype_truth ($@) { my ($value) = @_; return $value ? 1 : 0 }

ok(argument_value($^), '$^ remains defined after write');
ok(argument_value($~), '$~ remains defined after write');
is(argument_value($=), 60, '$= retains the selected handle default');
is(argument_value($-), 59, '$- accounts for the emitted format line');
is(argument_value($%), 0, '$% remains zero on the first page');

# Match the imported core helper's `my ($pass) = @_` path.
ok(argument_truth($^), '$^ remains true through argument binding');
ok(argument_truth($~), '$~ remains true through argument binding');
ok(argument_truth($=), '$= remains true through argument binding');
ok(argument_truth($-), '$- remains true through argument binding');
ok(defined argument_value($%), '$% remains defined through argument binding');

# `perl5_t/t/test.pl` declares ok as `sub ok ($@)`.  Its scalar prototype
# must preserve selected-handle magic just as an ordinary call does.
ok(prototype_truth($^), '$^ remains true through a scalar prototype');
ok(prototype_truth($~), '$~ remains true through a scalar prototype');
ok(prototype_truth($=), '$= remains true through a scalar prototype');
ok(prototype_truth($-), '$- remains true through a scalar prototype');
ok(defined prototype_truth($%), '$% remains defined through a scalar prototype');
