use strict;
use warnings;
use Test::More;

my $compiled = eval q{
    sub parser_test_fq_indirect_constructor_method {
        my %pid_tree = new Win32::Process::Info->Subprocesses(1);
    }
    1;
};

ok($compiled, 'fully qualified indirect constructor with method call compiles');
is($@, '', 'fully qualified constructor package need not be loaded to parse');
ok(!exists $INC{'Win32/Process/Info.pm'}, 'constructor package was not loaded');

done_testing;
