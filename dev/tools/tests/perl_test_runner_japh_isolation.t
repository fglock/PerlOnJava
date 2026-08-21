use strict;
use warnings;

use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;
use lib "$FindBin::Bin/../lib";
use PerlTestRunner::Scheduler qw(profile_for_test);

my $profile = profile_for_test('perl5_t/t/japh/abigail.t');
is_deeply($profile, { class => 'exclusive', weight => 3, exclusive => 1 },
    'mutating JAPH fixture is an exclusive scheduling barrier');

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile($root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $test_dir = File::Spec->catdir($temporary, 'perl5_t', 't');
my $lib_dir = File::Spec->catdir($temporary, 'perl5_t', 'lib');
my $japh_dir = File::Spec->catdir($test_dir, 'japh');
my $test_file = File::Spec->catfile($japh_dir, 'abigail.t');
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
make_path($japh_dir);
make_path($lib_dir);
write_file($test_file, "# private-overlay fixture\n");
write_file(File::Spec->catfile($test_dir, 'test.pl'), "1;\n");
write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
use strict;
use warnings;
use File::Spec;
use Time::HiRes qw(sleep);

my $token = $ENV{JAPH_ISOLATION_TOKEN};
my $linked = -x 'perl' && $ENV{PERLONJAVA_SHEBANG_TARGET} eq File::Spec->rel2abs($0);
my $layout = -d '../lib';
if (($ENV{JAPH_ISOLATION_MODE} || '') eq 'timeout') {
    kill 'TERM', $$;
    sleep 1;
}
open my $write, '>', 'progtmp001' or die "cannot create progtmp001: $!";
print {$write} $token;
close $write;
sleep 0.3;
open my $read, '<', 'progtmp001' or die "cannot read progtmp001: $!";
chomp(my $seen = <$read>);
close $read;
print "1..4\n";
print $linked ? "ok 1 - private perl link\n" : "not ok 1 - private perl link\n";
print $seen eq $token ? "ok 2 - private generated file\n" : "not ok 2 - private generated file\n";
print $layout ? "ok 3 - sibling library view\n" : "not ok 3 - sibling library view\n";
print (($ENV{JAPH_ISOLATION_MODE} || '') eq 'failure'
    ? "not ok 4 - requested failure\n" : "ok 4 - no requested failure\n");
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

my $old_dir = getcwd();
my $overlay_parent = File::Spec->catdir($temporary, 'runner-tmp');
make_path($overlay_parent);
local $ENV{TMPDIR} = $overlay_parent;
chdir $temporary or die "chdir $temporary failed: $!";
my @children;
for my $token (qw(first second)) {
    my $pid = fork();
    die "fork failed: $!" unless defined $pid;
    if ($pid == 0) {
        local $ENV{JAPH_ISOLATION_TOKEN} = $token;
        open STDOUT, '>', "$token.log" or die "redirect failed: $!";
        open STDERR, '>&', \*STDOUT or die "redirect failed: $!";
        exec $^X, $runner, '--jperl', $fake_jperl, '--strict-exit',
            '--jobs', '2', '--timeout', '10', 'perl5_t/t/japh/abigail.t';
        die "exec runner failed: $!";
    }
    push @children, [$pid, $token];
}
for my $child (@children) {
    waitpid($child->[0], 0);
    is($?, 0, "concurrent runner $child->[1] passes");
}

for my $case ([failure => 1], [timeout => 1]) {
    my ($mode, $expected_failure) = @$case;
    my $pid = fork();
    die "fork failed: $!" unless defined $pid;
    if ($pid == 0) {
        local $ENV{JAPH_ISOLATION_TOKEN} = $mode;
        local $ENV{JAPH_ISOLATION_MODE} = $mode;
        open STDOUT, '>', "$mode.log" or die "redirect failed: $!";
        open STDERR, '>&', \*STDOUT or die "redirect failed: $!";
        exec $^X, $runner, '--jperl', $fake_jperl, '--strict-exit',
            '--jobs', '2', '--timeout', '600', 'perl5_t/t/japh/abigail.t';
        die "exec runner failed: $!";
    }
    waitpid($pid, 0);
    isnt($?, 0, "$mode run reports non-success");
}
chdir $old_dir or die "restore cwd failed: $!";

ok(!-e File::Spec->catfile($test_dir, 'perl'),
    'authoritative test tree receives no perl link');
ok(!-e File::Spec->catfile($test_dir, 'progtmp001'),
    'authoritative test tree receives no generated program');
opendir my $overlays, $overlay_parent or die "cannot inspect overlay parent: $!";
my @leftovers = grep { /^perlonjava-abigail-/ } readdir $overlays;
closedir $overlays;
is_deeply(\@leftovers, [], 'private overlays are removed after success, failure, and timeout');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
