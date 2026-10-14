use strict;
use warnings;
use Test::More tests => 3;

my $eval_ok = eval q{
    package Issue1548EvalImportCaller;
    use Time::HiRes qw(time);
    our $before = time();
    1;
};
ok($eval_ok, 'eval compiles a module import');

ok(defined(&Issue1548EvalImportCaller::time),
    'eval import installs the function in the eval package');

like("$Issue1548EvalImportCaller::before", qr/\./,
    'eval import selects the high-resolution function');
