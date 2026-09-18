use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e',
    qq{BEGIN { \$^C = 1; }\ndump;\nCORE::dump;});
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

ok($? != 0, 'unqualified dump fails to compile');
is($output,
    "dump() must be written as CORE::dump() as of Perl 5.30 at -e line 2.\n",
    'unqualified dump has the CORE qualification diagnostic');

done_testing;
