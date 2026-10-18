use strict;
use warnings;
use File::Basename qw(dirname);
use File::Temp qw(tempfile);
use Test::More;

if ($^O eq 'MSWin32') {
    plan skip_all => 'timeout command is unavailable on Windows';
}

my $lifetime_test = dirname($0) . '/catalyst_request_body_tempfile_lifetime.t';
my ($log_fh, $log_path) = tempfile();
close($log_fh) or die "close child output file: $!";

open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
open(my $saved_stderr, '>&', \*STDERR) or die "save stderr: $!";
open(STDOUT, '>', $log_path) or die "redirect stdout: $!";
open(STDERR, '>&', \*STDOUT) or die "redirect stderr: $!";

my $runner = $^X eq 'jperl' ? './jperl' : $^X;
my $status = system('timeout', '300', $runner, $lifetime_test);

open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
open(STDERR, '>&', $saved_stderr) or die "restore stderr: $!";
close($saved_stdout);
close($saved_stderr);

open(my $log_read, '<', $log_path) or die "read child output: $!";
my $output = do { local $/; <$log_read> };
close($log_read);
unlink($log_path);

if ($output =~ /^1\.\.0 # SKIP (.+)$/m) {
    plan skip_all => $1;
}

is($status, 0, 'Catalyst tempfile lifetime regression passes in a child process');
like($output, qr/^1\.\.24$/m, 'child ran all 24 Catalyst lifetime assertions');
unlike($output,
    qr/DESTROY created new reference to dead object 'File::Temp'/,
    'normal tempfile cleanup does not report global-destruction resurrection');

done_testing;
