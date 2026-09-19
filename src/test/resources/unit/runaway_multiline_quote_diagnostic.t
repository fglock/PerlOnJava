use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = "q/\n/ time";
my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

like($output,
    qr/syntax error at -e line 2, near "\/ time"\n  \(Might be a runaway multi-line \/\/ string starting on line 1\)/,
    'a q delimiter repeated on the following line reports a runaway multiline quote');

done_testing;
