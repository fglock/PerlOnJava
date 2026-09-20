use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', "sub t1 {}\nsub t2 (}");
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

ok($? != 0, 'unterminated prototype fails to compile');
is($output, "Prototype not terminated at -e line 2.\n",
    'unterminated prototype has Perl-compatible diagnostic');

done_testing;
