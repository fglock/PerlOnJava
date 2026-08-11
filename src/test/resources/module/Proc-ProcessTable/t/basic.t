use strict;
use warnings;

use Test::More;
use Proc::ProcessTable;

my $table = Proc::ProcessTable->new(enable_ttys => 0)->table;
ok(ref($table) eq 'ARRAY', 'table returns an array reference');

my ($current) = grep { $_->pid == $$ } @$table;
ok($current, 'table contains the current process');
is($current->pid, $$, 'process pid accessor matches Perl pid');
ok(defined($current->cmndline), 'process command line is available');

done_testing;
