use strict;
use warnings;
use Test::More;
use IPC::Open3 qw(open3);
use Symbol qw(gensym);

my $source = <<'PERL';
use overload;
BEGIN { overload::constant q => sub {}; undef *^H }
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
undef(1,2);
"a"
PERL
my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $stderr = gensym;
my $pid = open3(undef, my $stdout, $stderr, $launcher, '-e', $source);
my $output = do { local $/; <$stdout> } . do { local $/; <$stderr> };
waitpid $pid, 0;

like($output,
    qr/Constant\(q\) unknown at -e line 12, near ""a""\n/,
    'the capped q constant diagnostic retains its reduced q label for double quotes');

done_testing;
