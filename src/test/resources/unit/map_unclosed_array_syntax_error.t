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
    'sub all (&@); all { $_->[0] } map { [ }');
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid($pid, 0);

ok($? != 0, 'an array literal closed by a curly bracket does not compile');
like($output, qr/syntax error.*near "\[ \}"/s,
    'mismatched array delimiter appears in the syntax context');

done_testing;
