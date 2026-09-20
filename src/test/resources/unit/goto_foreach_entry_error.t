use strict;
use warnings;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);
use Test::More;

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
if ($^X eq 'jperl' && !-f 'target/perlonjava-5.44.1.jar') {
    plan skip_all => 'nested jperl launcher requires the development jar';
}

my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e',
    "goto target;\nforeach (1) { target: }");
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid($pid, 0);

ok($? != 0, 'top-level goto cannot enter a foreach body');
like($output, qr/Can't "goto" into the middle of a foreach loop/,
    'top-level goto reports a foreach-entry diagnostic');
like($output, qr/at .* line 2\./,
    'top-level goto reports the destination label line');

my $ok = eval q{
    goto target;
    foreach (1) { target: }
    1;
};
ok(!$ok, 'goto cannot enter a foreach body');
like($@, qr/Can't "goto" into the middle of a foreach loop/,
    'goto reports a foreach-entry diagnostic');

done_testing;
