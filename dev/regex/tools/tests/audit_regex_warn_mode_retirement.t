use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Open3;
use JSON::PP;
use Symbol qw(gensym);
use Test::More;

my $repository = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $tool = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
    'audit_regex_warn_mode_retirement.pl');

subtest 'clean retirement fixture passes' => sub {
    my $root = fixture('clean');
    my ($status, $record) = run_audit($root);
    is($status, 0, 'clean fixture passes');
    ok($record->{passed}, 'record is passed');
    is_deeply($record->{violations}, [], 'clean fixture has no violations');
};

subtest 'every active warn-mode surface is fail-closed' => sub {
    for my $case (
        ['runner', 'dev/tools/perl_test_runner.pl', "local \$ENV{JPERL_UNIMPLEMENTED} = 'warn';\n"],
        ['distropref', 'src/main/perl/lib/PerlOnJava/CpanDistroprefs/Logger-Simple.yml', "test:\n  env:\n    JPERL_UNIMPLEMENTED: warn\n"],
        ['runtime', 'src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java', "System.getenv(\"JPERL_UNIMPLEMENTED\");\n"],
        ['guidance', 'AGENTS.md', "JPERL_UNIMPLEMENTED=warn ./jperl re/pat.t\n"],
        ['regex-unit', 'src/test/resources/unit/regex/fatal.t', "local \$ENV{JPERL_UNIMPLEMENTED} = 'warn';\n"],
        ['new-runtime-helper', 'src/main/java/org/perlonjava/runtime/regex/WarnModeHelper.java', "System.getenv(\"JPERL_UNIMPLEMENTED\");\n"],
        ['new-skill-guidance', '.agents/skills/new/SKILL.md', "JPERL_UNIMPLEMENTED=warn\n"],
    ) {
        my $root = fixture($case->[0], $case->[1], $case->[2]);
        my ($status, $record) = run_audit($root);
        isnt($status, 0, "$case->[0] is rejected");
        ok(!$record->{passed}, "$case->[0] record is failed");
        is($record->{violations}[0]{path}, $case->[1],
            "$case->[0] names the active surface");
    }
};

subtest 'moved runtime anchor is rejected' => sub {
    my $root = fixture('moved-runtime');
    my $anchor = File::Spec->catfile($root, split m{/},
        'src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java');
    unlink $anchor or die "$anchor: $!";
    my $moved = File::Spec->catfile($root, split m{/},
        'src/main/java/org/perlonjava/runtime/regex/MovedRuntimeRegex.java');
    write_file($moved, "System.getenv(\"JPERL_UNIMPLEMENTED\");\n");
    my ($status, $record) = run_audit($root);
    isnt($status, 0, 'moved runtime anchor is rejected');
    is($record->{violations}[0]{path},
        'src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java',
        'missing structural runtime anchor is reported first');
    is($record->{violations}[1]{path},
        'src/main/java/org/perlonjava/runtime/regex/MovedRuntimeRegex.java',
        'moved runtime hook is also reported');
};

done_testing;

sub fixture {
    my ($name, $relative, $contents) = @_;
    my $root = tempdir(CLEANUP => 1);
    write_file(File::Spec->catfile($root, split m{/}, 'dev/tools/perl_test_runner.pl'), "\n");
    write_file(File::Spec->catfile($root, split m{/},
        'src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java'), "\n");
    if (defined $relative) {
        my $path = File::Spec->catfile($root, split m{/}, $relative);
        write_file($path, $contents);
    }
    return $root;
}

sub run_audit {
    my ($root) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $tool, '--root', $root);
    local $/;
    my $output = <$stdout> // '';
    my $stderr = <$error> // '';
    waitpid($pid, 0);
    die "audit stderr: $stderr" if length $stderr;
    return ($? >> 8, JSON::PP->new->decode($output));
}

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory);
    open my $fh, '>:raw', $path or die "$path: $!";
    print {$fh} $contents;
    close $fh or die "$path: $!";
}
