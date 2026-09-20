use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', '1e--5');
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

ok($? != 0, 'malformed decimal exponent fails to compile');
like($output,
    qr{\ABareword found where operator expected \(Missing operator before "e"\?\) at -e line 1, near "1e"\nsyntax error at -e line 1, near "1e"\nExecution of -e aborted due to compilation errors\.\n\z},
    'malformed decimal exponent reports the bare marker diagnostics');

done_testing;
