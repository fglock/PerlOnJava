use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = "format=\n@\n=h\n=cut\n";
my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

like($output, qr/syntax error at -e line 4, next token \?\?\?/,
    'malformed format body keeps Perl\'s syntax-error diagnostic');

done_testing;
