use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use File::Spec;
use Cwd qw(abs_path getcwd);
use lib 'src/main/perl/lib';
use PerlOnJava::Process qw(run_process);
use CPAN::FindDependencies::MakeMaker qw(getreqs_from_mm);

my $result = run_process(
    argv => [ $^X, '-e', 'print "out\\n"; warn "err\\n"' ],
    timeout => 10,
);
is($result->{exit_code}, 0, 'argv process exits successfully');
like($result->{output}, qr/out\n/, 'stdout is captured');
like($result->{output}, qr/err\n/, 'stderr is captured');
ok(!$result->{timed_out}, 'successful process does not time out');

my $tee_path = File::Spec->catfile(tempdir(CLEANUP => 1), 'live-output.txt');
open my $saved_stdout, '>&', STDOUT or die "duplicate STDOUT: $!";
open STDOUT, '>', $tee_path or die "open $tee_path: $!";
$result = run_process(
    argv => [
        $^X,
        '-e',
        '$|=1; print "live marker\\n"; select undef,undef,undef,0.5; '
            . 'open my $fh,"<",$ARGV[0] or exit 7; '
            . 'local $/; exit((<$fh> || "") =~ /live marker/ ? 0 : 8)',
        $tee_path,
    ],
    timeout => 10,
    tee => 1,
);
open STDOUT, '>&', $saved_stdout or die "restore STDOUT: $!";
close $saved_stdout;
is($result->{exit_code}, 0, 'tee output is visible before the child exits');
like($result->{output}, qr/live marker/, 'tee mode also retains captured output');

my $dir = tempdir(CLEANUP => 1);
$result = run_process(
    argv => [ $^X, '-MCwd=getcwd', '-e', 'print getcwd()' ],
    cwd => $dir,
    timeout => 10,
);
is(abs_path($result->{output}), abs_path($dir), 'working directory is explicit');

my $original_dir = getcwd();
my $implicit_dir = tempdir(CLEANUP => 1);
chdir $implicit_dir or die "chdir $implicit_dir: $!";
$result = run_process(
    argv => [ $^X, '-MCwd=getcwd', '-e', 'print getcwd()' ],
    timeout => 10,
);
chdir $original_dir or die "chdir $original_dir: $!";
is(abs_path($result->{output}), abs_path($implicit_dir),
    'child inherits the current logical working directory');

$result = run_process(
    argv => [ $^X, '-e', 'sleep 5' ],
    timeout => 1,
);
ok($result->{timed_out}, 'deadline terminates a long-running process');
isnt($result->{exit_code}, 0, 'timed-out process does not report success');

my $requirements = getreqs_from_mm(<<'MAKEFILE_PL');
use ExtUtils::MakeMaker;
WriteMakefile(
    NAME => 'Local::ProcessFixture',
    VERSION => '0.01',
    PREREQ_PM => { 'Example::Dependency' => '1.23' },
);
MAKEFILE_PL
is_deeply(
    $requirements,
    { 'Example::Dependency' => '1.23' },
    'MakeMaker dependency extraction uses the shared process service',
);

done_testing;
