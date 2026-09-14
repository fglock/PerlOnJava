use v5.40;
use Test::More;
use File::Temp qw(tempfile);
use IPC::Open3;

my ($fh, $path) = tempfile('perlonjava-regex-heredoc-XXXX', SUFFIX => '.pl', UNLINK => 1);
print {$fh} "s;\@{<<a;\n";
close $fh or die "Cannot close $path: $!";

my $launcher = $^X eq 'jperl' ? './jperl' : $^X;
my $timeout_command = $ENV{PERLONJAVA_TIMEOUT_COMMAND} // 'timeout';
my ($in, $out);
my $pid = open3($in, $out, undef, $timeout_command, '60', $launcher, $path);
close $in;
local $/;
my $output = <$out> // '';
close $out;
waitpid $pid, 0;

ok($? != 0, 'an interpolated unterminated heredoc rejects compilation');
like($output, qr/Can't find string terminator "a" anywhere before EOF/,
    'the heredoc terminator diagnostic wins over the unterminated regex diagnostic');

done_testing;
