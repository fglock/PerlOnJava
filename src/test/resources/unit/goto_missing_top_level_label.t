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
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', 'goto MISSING_LABEL');
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid($pid, 0);

like($output, qr/Can't find label MISSING_LABEL/,
    'a top-level goto to a missing label reports Perl\'s diagnostic');

done_testing;
