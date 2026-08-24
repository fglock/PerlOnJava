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

my $repository = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $audit = File::Spec->catfile($repository, 'dev', 'regex', 'tools',
    'audit_regex_warn_mode_retirement.pl');

my %source = map {
    $_ => read_file(File::Spec->catfile($repository, split m{/}, $_))
} qw(
    dev/regex/tools/run_regex_acceptance.pl
    dev/regex/tools/run_cpan_acceptance.pl
    dev/regex/tools/check_acceptance_manifest.pl
);

subtest 'acceptance children clear inherited warn mode' => sub {
    my $regex = $source{'dev/regex/tools/run_regex_acceptance.pl'};
    for my $name ('jperl-version', 'jvm-runner', 'interpreter-runner') {
        like($regex,
            qr/name\s*=>\s*'\Q$name\E'.*?environment\s*=>\s*\{.*?JPERL_UNIMPLEMENTED\s*=>\s*undef/s,
            "regex acceptance $name child clears warn mode");
    }
    like($regex,
        qr/defined\s+\$environment->\{\$key\}.*?delete\s+\$ENV\{\$key\}/s,
        'regex acceptance deletes environment keys whose contract value is undef');

    my $cpan = $source{'dev/regex/tools/run_cpan_acceptance.pl'};
    like($cpan,
        qr/my\s+\$version_run\s*=\s*run_child\(.*?JPERL_UNIMPLEMENTED\s*=>\s*undef/s,
        'CPAN identity child clears warn mode');
    like($cpan,
        qr/my\s+%environment\s*=\s*\(.*?JPERL_UNIMPLEMENTED\s*=>\s*undef/s,
        'every CPAN target/backend child clears warn mode');
    like($cpan,
        qr/defined\s+\$arg\{environment\}\{\$key\}.*?delete\s+\$ENV\{\$key\}/s,
        'CPAN acceptance deletes environment keys whose contract value is undef');
};

subtest 'CPAN evidence seals the unset contract' => sub {
    my $cpan = $source{'dev/regex/tools/run_cpan_acceptance.pl'};
    like($cpan,
        qr/environment\s*=>\s*\{\s*map.*?JPERL_UNIMPLEMENTED/s,
        'mode evidence retains the unset key');
    like($cpan,
        qr/environment_sha256.*?JPERL_UNIMPLEMENTED/s,
        'mode environment hash covers the unset key');
    like($cpan, qr/my\s+\$document\s*=\s*\{\s*schema_version\s*=>\s*2/s,
        'new CPAN acceptance evidence uses the strict environment schema');
    like($cpan,
        qr/exists\(\$environment->\{JPERL_UNIMPLEMENTED\}\)\s*&&\s*!defined\(\$environment->\{JPERL_UNIMPLEMENTED\}\)/s,
        'safe-resume validation rejects enabled or omitted warn mode');

    my $manifest = $source{'dev/regex/tools/check_acceptance_manifest.pl'};
    like($manifest, qr/\$cpan_schema\s*==\s*1\s*\|\|\s*\$cpan_schema\s*==\s*2/,
        'sealed-manifest validation retains explicit schema-1 compatibility');
    like($manifest,
        qr/exists\(\$environment->\{JPERL_UNIMPLEMENTED\}\)\s*&&\s*!defined\(\$environment->\{JPERL_UNIMPLEMENTED\}\)/s,
        'sealed-manifest validation requires explicit unset warn mode');
    like($manifest,
        qr/my\s+\@environment_keys\s*=.*?JPERL_UNIMPLEMENTED/s,
        'sealed-manifest environment hash covers the unset key');
};

subtest 'retirement audit rejects producer and sanitizer regressions' => sub {
    my $clean = fixture();
    my ($clean_status, $clean_record) = run_audit($clean);
    is($clean_status, 0, 'clean fixture passes');
    ok($clean_record->{passed}, 'clean fixture has a passing record');

    my $producer = fixture();
    write_file(File::Spec->catfile($producer, split m{/},
        'dev/tools/new_acceptance.pl'),
        "JPERL_UNIMPLEMENTED=warn ./jperl test.t\n");
    my ($producer_status, $producer_record) = run_audit($producer);
    isnt($producer_status, 0, 'new executable tooling producer is rejected');
    ok(grep($_->{path} eq 'dev/tools/new_acceptance.pl',
            @{$producer_record->{violations}}),
        'producer violation identifies the new tool');

    for my $relative (qw(
        dev/regex/tools/run_regex_acceptance.pl
        dev/regex/tools/run_cpan_acceptance.pl
    )) {
        my $missing = fixture();
        write_file(File::Spec->catfile($missing, split m{/}, $relative), "\n");
        my ($status, $record) = run_audit($missing);
        isnt($status, 0, "$relative without sanitizer is rejected");
        ok(grep($_->{path} eq $relative
                && $_->{reason} =~ /does not clear inherited/,
                @{$record->{violations}}),
            "$relative has an exact missing-sanitizer violation");
    }
};

done_testing;

sub fixture {
    my $root = tempdir(CLEANUP => 1);
    write_file(File::Spec->catfile($root, split m{/},
        'dev/tools/perl_test_runner.pl'), "\n");
    for my $relative (qw(
        dev/regex/tools/run_regex_acceptance.pl
        dev/regex/tools/run_cpan_acceptance.pl
    )) {
        write_file(File::Spec->catfile($root, split m{/}, $relative),
            "environment => { JPERL_UNIMPLEMENTED => undef };\n");
    }
    write_file(File::Spec->catfile($root, split m{/},
        'src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java'), "\n");
    return $root;
}

sub run_audit {
    my ($root) = @_;
    my $error = gensym;
    my $pid = open3(undef, my $stdout, $error, $^X, $audit,
        '--root', $root);
    local $/;
    my $output = <$stdout> // '';
    my $stderr = <$error> // '';
    waitpid($pid, 0);
    die "audit stderr: $stderr" if length $stderr;
    return ($? >> 8, JSON::PP->new->decode($output));
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $contents = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $contents;
}

sub write_file {
    my ($path, $contents) = @_;
    my (undef, $directory) = File::Spec->splitpath($path);
    make_path($directory);
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $contents;
    close $fh or die "Cannot close $path: $!\n";
}
