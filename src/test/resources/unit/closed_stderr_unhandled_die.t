use strict;
use warnings;
use Test::More tests => 2;
use IPC::Open3;
use Symbol qw(gensym);

my $err = gensym();
my $out = gensym();
my $pid = open3(undef, $out, $err, $^X, '-e', 'close STDERR; die;');
local $/;
my $stdout = <$out> // '';
my $stderr = <$err> // '';
waitpid($pid, 0);

is($stdout, '', 'closed STDERR does not leak an unhandled die to stdout');
is($stderr, '', 'closed STDERR suppresses the unhandled die diagnostic');
