use strict;
use warnings;

use Config;
use File::Spec;
use File::Temp qw(tempdir);
use IPC::Open3;
use Test::More;

plan skip_all => 'taint-mode subprocess test is Unix-only'
    if $^O eq 'MSWin32';

my @path = split /\Q$Config{path_sep}\E/, $ENV{PATH} // '';
my ($timeout) = grep { -x $_ }
    map { File::Spec->catfile($_, 'timeout') } @path;
plan skip_all => 'timeout utility is unavailable' unless $timeout;

my $hidden_lib = tempdir(CLEANUP => 1);
my $explicit_lib = tempdir(CLEANUP => 1);
my $perl_on_java = $ENV{PERLONJAVA_EXECUTABLE};
my @runners = $perl_on_java && -x $perl_on_java
    ? (
        ['PerlOnJava JVM', $perl_on_java],
        ['PerlOnJava interpreter', $perl_on_java, '--interpreter'],
    )
    : (['system Perl', $^X]);

my $java = $ENV{PERLONJAVA_JAVA_BIN};
($java) = grep { -x $_ }
    map { File::Spec->catfile($_, 'java') } @path
    unless defined($java) && -x $java;

plan tests => 2 * @runners;

for my $runner (@runners) {
    my ($name, $executable, @backend_args) = @$runner;
    my ($input, $output);
    {
        local $ENV{PERL5LIB} = $hidden_lib;
        local $ENV{PERLONJAVA_JAVA_BIN} = $java if $java;

        my $code = 'print((grep { $_ eq $ENV{TAINT_TEST_HIDDEN} } @INC) ? "hidden=1\\n" : "hidden=0\\n");'
            . 'print((grep { $_ eq $ENV{TAINT_TEST_EXPLICIT} } @INC) ? "explicit=1\\n" : "explicit=0\\n");';
        local $ENV{TAINT_TEST_HIDDEN} = $hidden_lib;
        local $ENV{TAINT_TEST_EXPLICIT} = $explicit_lib;

        my $pid = open3(
            $input, $output, undef,
            $timeout, '30', $executable, @backend_args,
            "-I$explicit_lib", '-T', '-e', $code,
        );
        close $input;
        my $result = do { local $/; <$output> };
        close $output;
        waitpid($pid, 0);

        is($?, 0, "$name -T child exits successfully");
        is($result, "hidden=0\nexplicit=1\n",
            "$name -T ignores PERL5LIB and keeps explicit -I");
    }
}

done_testing;
