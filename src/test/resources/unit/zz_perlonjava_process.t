use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use Cwd qw(abs_path);
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

my $dir = tempdir(CLEANUP => 1);
$result = run_process(
    argv => [ $^X, '-MCwd=getcwd', '-e', 'print getcwd()' ],
    cwd => $dir,
    timeout => 10,
);
is(abs_path($result->{output}), abs_path($dir), 'working directory is explicit');

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
