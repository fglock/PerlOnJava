use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = "use overload; BEGIN { overload::constant q => sub {}; undef *^H }\n\"a\"";
my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

like($output,
    qr/\AConstant\(q\) unknown at -e line 2, near ""a""\n/,
    'clearing the q constant hook preserves the q diagnostic for double quotes');

done_testing;
