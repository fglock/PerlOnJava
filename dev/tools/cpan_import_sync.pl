#!/usr/bin/env perl
use strict;
use warnings;

use Archive::Tar;
use Config ();
use Cwd qw(abs_path getcwd);
use Digest::SHA ();
use File::Basename qw(dirname basename);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Getopt::Long qw(GetOptions);
use HTTP::Tiny;
use JSON::PP qw(decode_json encode_json);

my %option = (
    root       => getcwd(),
    registry   => 'dev/tools/cpan-import-registry.json',
    stage_dir  => '',
    fixture_dir => '',
    overlay_dir => '',
);
my $help;
GetOptions(
    'root=s'        => \$option{root},
    'registry=s'    => \$option{registry},
    'stage-dir=s'   => \$option{stage_dir},
    'fixture-dir=s' => \$option{fixture_dir},
    'overlay-dir=s' => \$option{overlay_dir},
    'module=s'      => \$option{module},
    'only=s'        => \$option{module},
    'check'         => \$option{check},
    'update'        => \$option{update},
    'apply'         => \$option{apply},
    'help'          => \$help,
) or usage(2);
usage(0) if $help;
my $mode_count = grep { $_ } @option{qw(check update apply)};
usage(2) if $mode_count > 1;
$option{check} = 1 unless $mode_count;

$option{root} = abs_path($option{root}) || die "Cannot resolve repository root\n";
$option{registry} = abs_path(absolute_from_root($option{registry}))
    || die "Cannot resolve registry path\n";
$option{stage_dir} ||= tempdir('perlonjava-cpan-import-XXXXXX', TMPDIR => 1, CLEANUP => 0);
$option{stage_dir} = absolute_path($option{stage_dir});
$option{fixture_dir} = absolute_path($option{fixture_dir}) if $option{fixture_dir};
$option{overlay_dir} = absolute_path($option{overlay_dir}) if $option{overlay_dir};

my $registry = read_json($option{registry});
die "Unsupported CPAN import registry schema\n"
    unless ref($registry) eq 'HASH'
        && ($registry->{schema_version} || 0) == 1
        && ref($registry->{modules}) eq 'ARRAY';

my (%module_by_name, %distribution_by_name);
for my $entry (@{$registry->{modules}}) {
    die "Invalid CPAN import registry entry\n"
        unless ref($entry) eq 'HASH'
            && $entry->{distribution}
            && $entry->{port_mode}
            && $entry->{upstream_distribution_version};
    die "Duplicate CPAN distribution $entry->{distribution}\n"
        if $distribution_by_name{$entry->{distribution}}++;
    for my $provider (@{$entry->{providers} || []}) {
        die "Invalid provider entry in $entry->{distribution}\n"
            unless ref($provider) eq 'HASH' && $provider->{kind} && $provider->{path};
        next unless $provider->{kind} eq 'perl_version';
        die "Invalid Perl provider entry in $entry->{distribution}\n"
            unless $provider->{module};
        die "Duplicate provider $provider->{module}\n"
            if $module_by_name{$provider->{module}};
        $module_by_name{$provider->{module}} = { %$provider, distribution => $entry->{distribution} };
    }
}

my @selected = grep {
    !$option{module}
        || $_->{distribution} eq $option{module}
        || (defined($option{module})
            && grep { defined($_->{module}) && $_->{module} eq $option{module} }
                @{$_->{providers} || []})
} @{$registry->{modules}};
die "No CPAN distribution registered for '$option{module}'\n"
    if $option{module} && !@selected;

if ($option{check}) {
    my $errors = 0;
    for my $entry (@selected) {
        $errors += check_entry($entry);
    }
    print "contracts are consistent (", scalar(@selected), " distributions)\n"
        unless $errors;
    exit $errors ? 1 : 0;
}

make_path($option{stage_dir});
my $errors = 0;
if ($option{apply}) {
    my $ok = eval { apply_releases(\@selected); 1 };
    if (!$ok) {
        my $error = $@ || 'unknown apply error';
        $error =~ s/\s+\z//;
        warn "CPAN update apply: $error\n";
        $errors++;
    }
} else {
    for my $entry (@selected) {
        my $ok = eval {
            stage_release($entry);
            1;
        };
        if (!$ok) {
            my $error = $@ || 'unknown staging error';
            $error =~ s/\s+\z//;
            warn "$entry->{distribution}: $error\n";
            $errors++;
        }
    }
}
exit $errors ? 1 : 0;

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: cpan_import_sync.pl [--check | --update | --apply] [--only DIST|MODULE]
       [--root DIR] [--registry FILE] [--stage-dir DIR]

--check       Verify the pinned versions in the registry without network access.
--update      Resolve a stable CPAN release and stage it without changing source.
--apply       Stage releases, validate them in an isolated worktree, then apply.
--only        Select one distribution or primary module.
USAGE
    exit $status;
}

sub check_entry {
    my ($entry) = @_;
    my @errors;
    for my $path (@{$entry->{tests} || []}) {
        push @errors, "declared test fixture $path is missing" unless -e absolute_from_root($path);
    }
    for my $mapping (@{$entry->{files} || []}) {
        for my $field (qw(source target)) {
            my $path = $mapping->{$field} || '';
            push @errors, "unsafe $field path '$path'"
                if File::Spec->file_name_is_absolute($path)
                    || $path =~ m{(?:\A|/)\.\.(?:/|\z)}
                    || $path =~ m{\A[A-Za-z]:};
        }
    }
    for my $provider (@{$entry->{providers} || []}) {
        my $actual = read_declared_version($provider);
        if (!defined $actual) {
            push @errors, "$provider->{module}: cannot read version from $provider->{path}";
            next;
        }
        if ($actual ne $provider->{version}) {
            push @errors, "$provider->{module} declares $actual; registry pins $provider->{version}";
        }
    }
    if (@errors) {
        warn "$entry->{distribution}: $_\n" for @errors;
        return scalar @errors;
    }
    print "$entry->{distribution}: contracts are consistent\n";
    return 0;
}

sub read_declared_version {
    my ($provider) = @_;
    my $path = absolute_from_root($provider->{path});
    open my $fh, '<', $path or return;
    local $/;
    my $source = <$fh>;
    close $fh;

    my $kind = $provider->{kind} || '';
    if ($kind eq 'perl_version') {
        my $module = quotemeta($provider->{module});
        return $1 if $source =~ /\$VERSION\s*=\s*['"]([^'"]+)['"]/s;
        return $1 if $source =~ /package\s+$module\s*;.*?\$VERSION\s*=\s*([\d.]+)/s;
        return;
    }
    if ($kind eq 'java_static_string') {
        return $1 if $source =~ /\b\Q$provider->{symbol}\E\s*=\s*"([^"]+)"/;
        return;
    }
    if ($kind eq 'java_runtime_scalar') {
        return $1 if $source =~ /getGlobalVariable\("\Q$provider->{symbol}\E"\)\s*\.set\(new RuntimeScalar\("([^"]+)"\)\)/s;
        return;
    }
    if ($kind eq 'gradle_catalog') {
        my $symbol = quotemeta($provider->{symbol} || '');
        return $1 if $source =~ /^\s*\Q$provider->{symbol}\E\s*=\s*"([^"]+)"\s*$/m;
        return;
    }
    die "Unknown provider field kind '$kind'\n";
}

sub stage_release {
    my ($entry) = @_;
    my $release = locate_release($entry);
    die "release distribution '$release->{name}' does not match '$entry->{distribution}'\n"
        unless ($release->{name} || '') eq $entry->{distribution};
    die "release $release->{version} is not stable\n"
        unless ($release->{maturity} || '') =~ /\A(?:released|stable)\z/i;
    die "release archive checksum is missing\n"
        unless ($release->{checksum_sha256} || '') =~ /\A[0-9a-f]{64}\z/i;

    my $archive_path = fetch_archive($entry, $release);
    my $actual_checksum = sha256_file($archive_path);
    die "SHA-256 mismatch for $release->{archive}\n"
        unless lc($actual_checksum) eq lc($release->{checksum_sha256});

    check_candidate_prerequisites($entry, $archive_path, $release->{version});
    my $destination = File::Spec->catdir($option{stage_dir}, "$entry->{distribution}-$release->{version}");
    die "stage destination already exists: $destination\n" if -e $destination;
    extract_archive($archive_path, $destination, $entry->{distribution}, $release->{version});
    synchronize_candidate($entry, $release, $destination);
    write_file(File::Spec->catfile($destination, 'PERLONJAVA-PORTING.txt'),
        porting_report($entry, $release));
    print "$entry->{distribution}: staged $release->{version} at $destination\n";
    return $destination;
}

sub apply_releases {
    my ($entries) = @_;
    ensure_clean_checkout();
    my @staged = map { [$_, stage_release($_)] } @$entries;
    my $worktree = tempdir('perlonjava-cpan-worktree-XXXXXX', TMPDIR => 1, CLEANUP => 0);
    rmdir $worktree or die "cannot prepare worktree path $worktree: $!\n";
    my $added = system('git', '-C', $option{root}, 'worktree', 'add', '--detach', $worktree, 'HEAD');
    if ($added != 0) {
        rmdir $worktree;
        die "cannot create validation worktree\n";
    }

    my $ok = eval {
        for my $pair (@staged) {
            apply_candidate($pair->[0], $pair->[1], $worktree);
            validate_upstream_tests($pair->[0], $pair->[1]);
        }
        validate_worktree(\@staged, $worktree);
        apply_validated_diff($worktree);
        1;
    };
    my $error = $@;
    my $removed = system('git', '-C', $option{root}, 'worktree', 'remove', '--force', $worktree);
    warn "cannot remove validation worktree $worktree\n" if $removed != 0;
    die $error unless $ok;
    print "applied validated CPAN releases: ", join(', ', map {
        $_->[0]{distribution} . ' ' . basename($_->[1])
    } @staged), "\n";
}

sub ensure_clean_checkout {
    my $status = `git -C "$option{root}" status --porcelain`;
    die "--apply requires a clean checkout; no source files were changed\n" if length $status;
}

sub apply_candidate {
    my ($entry, $destination, $worktree) = @_;
    for my $mapping (@{$entry->{files} || []}) {
        my $source = File::Spec->catfile($destination, 'candidate', split m{/}, $mapping->{target});
        die "staged mapping is missing $mapping->{source}\n" unless -e $source;
        my $target = File::Spec->catfile($worktree, split m{/}, $mapping->{target});
        if (-d $source) {
            find({
                wanted => sub {
                    return unless -f $File::Find::name;
                    my $relative = File::Spec->abs2rel($File::Find::name, $source);
                    my $out = File::Spec->catfile($target, $relative);
                    make_path(dirname($out));
                    copy_file($File::Find::name, $out);
                },
                no_chdir => 1,
            }, $source);
        } else {
            make_path(dirname($target));
            copy_file($source, $target);
        }
    }

    # The selected release is recorded in the staged report; extract it from
    # the staged directory name so live and fixture releases share this path.
    my ($version) = basename($destination) =~ /\Q$entry->{distribution}\E-(.+)\z/;
    die "cannot determine staged release version\n" unless $version;
    update_version_targets($entry, $version, $worktree);
    my $registry_file = File::Spec->catfile($worktree, File::Spec->abs2rel($option{registry}, $option{root}));
    my $registry = read_json($registry_file);
    for my $registered (@{$registry->{modules}}) {
        next unless $registered->{distribution} eq $entry->{distribution};
        $registered->{upstream_distribution_version} = $version;
        for my $provider (@{$registered->{providers} || []}) {
            $provider->{version} = $version
                if $provider->{same_version_as_upstream} || $entry->{port_mode} eq 'full-import';
        }
    }
    write_file($registry_file, JSON::PP->new->canonical->pretty->encode($registry));
}

sub update_version_targets {
    my ($entry, $version, $worktree) = @_;
    for my $provider (@{$entry->{providers} || []}) {
        next unless $provider->{same_version_as_upstream};
        my $path = File::Spec->catfile($worktree, split m{/}, $provider->{path});
        open my $fh, '<:raw', $path or die "cannot read version target $path: $!\n";
        local $/;
        my $source = <$fh>;
        close $fh or die "cannot close version target $path: $!\n";
        my $count;
        if ($provider->{kind} eq 'perl_version') {
            $count = ($source =~ s/(\$VERSION\s*=\s*)(?:'[^']*'|"[^"]*"|[\w.]+)/$1 . "'$version'"/e);
        } elsif ($provider->{kind} eq 'java_static_string') {
            my $symbol = quotemeta($provider->{symbol});
            $count = ($source =~ s/(\b$symbol\s*=\s*")[^"]*(")/$1 . $version . $2/e);
        } elsif ($provider->{kind} eq 'java_runtime_scalar') {
            my $symbol = quotemeta($provider->{symbol});
            $count = ($source =~ s/(getGlobalVariable\("$symbol"\)\s*\.set\(new RuntimeScalar\(")[^"]*("\)\))/ $1 . $version . $2 /e);
        } else {
            die "unsupported version target kind $provider->{kind}\n";
        }
        die "cannot update version target $provider->{path}\n" unless $count;
        write_file($path, $source);
    }
}

sub validate_upstream_tests {
    my ($entry, $destination) = @_;
    my $lib = File::Spec->catdir($destination, 'lib');
    if (-d File::Spec->catdir($destination, 't')) {
        local $ENV{PERL5LIB} = join($Config::Config{path_sep} || ':', $lib,
            ($ENV{PERL5LIB} || ''));
        my $status = system('timeout', '3600', 'prove', '-r', File::Spec->catdir($destination, 't'));
        die "system Perl tests failed for $entry->{distribution}\n" if $status != 0;
    }
}

sub validate_worktree {
    my ($staged, $worktree) = @_;
    my $make = system('nice', '-n', '19', 'make', '-C', $worktree);
    die "make failed in validation worktree\n" if $make != 0;
    my $bundled = system('nice', '-n', '19', 'make', '-C', $worktree, 'test-bundled-modules');
    die "make test-bundled-modules failed in validation worktree\n" if $bundled != 0;
    for my $pair (@$staged) {
        audit_module_tests_discovered($pair->[0], $pair->[1], $worktree);
    }
}

sub audit_module_tests_discovered {
    my ($entry, $destination, $worktree) = @_;
    my $report = File::Spec->catfile($worktree, 'build', 'test-results', 'testModule',
        'TEST-org.perlonjava.ModuleTestExecutionTest.xml');
    my $skip_file = File::Spec->catfile($worktree, 'src', 'test', 'java', 'org',
        'perlonjava', 'ModuleTestExecutionTest.java');
    open my $skip_fh, '<', $skip_file or die "cannot read module test skip list: $!\n";
    local $/;
    my $skip_source = <$skip_fh>;
    close $skip_fh;
    my %skipped = map { $_ => 1 } $skip_source =~ /"(module\/[^\"]+\.t)"/g;

    my @expected;
    for my $mapping (@{$entry->{files} || []}) {
        next unless $mapping->{target} =~ m{\Asrc/test/resources/module/};
        my $source = File::Spec->catdir($destination, 'candidate', split m{/}, $mapping->{target});
        my @files;
        if (-d $source) {
            find({
                wanted => sub {
                    push @files, $File::Find::name
                        if -f $File::Find::name && $File::Find::name =~ /\.t\z/;
                },
                no_chdir => 1,
            }, $source);
        } elsif ($source =~ /\.t\z/ && -f $source) {
            @files = ($source);
        }
        for my $file (@files) {
            my $relative = File::Spec->abs2rel($file, $source);
            my $test_path = $mapping->{target};
            $test_path =~ s{\Asrc/test/resources/}{};
            $test_path = File::Spec->catfile($test_path, $relative) if -d $source;
            $test_path =~ s{\\}{/}g;
            push @expected, $test_path;
        }
    }
    return unless @expected;
    open my $report_fh, '<', $report or die "bundled module test report is missing\n";
    my $report_text = do { local $/; <$report_fh> };
    close $report_fh;
    for my $test (@expected) {
        next if $skipped{$test};
        die "refreshed test $test was not discovered by ModuleTestExecutionTest\n"
            unless index($report_text, $test) >= 0;
    }
}

sub apply_validated_diff {
    my ($worktree) = @_;
    my $staged = system('git', '-C', $worktree, 'add', '-A');
    die "cannot stage validated worktree changes\n" if $staged != 0;
    my ($patch_fh, $patch_path) = File::Temp::tempfile('perlonjava-cpan-apply-XXXXXX',
        DIR => $worktree, UNLINK => 0);
    close $patch_fh or die "cannot close patch file: $!\n";
    my $ok = eval {
        my $saved = system('git', '-C', $worktree, 'diff', '--cached', '--binary', 'HEAD', "--output=$patch_path");
        die "cannot capture validated worktree changes\n" if $saved != 0;
        my $checked = system('git', '-C', $option{root}, 'apply', '--check', $patch_path);
        die "validated update no longer applies cleanly\n" if $checked != 0;
        my $applied = system('git', '-C', $option{root}, 'apply', $patch_path);
        die "cannot apply validated update\n" if $applied != 0;
        1;
    };
    my $error = $@;
    unlink $patch_path or warn "cannot remove temporary patch $patch_path: $!\n";
    die $error unless $ok;
}

sub locate_release {
    my ($entry) = @_;
    if ($option{fixture_dir}) {
        my $path = File::Spec->catfile($option{fixture_dir}, 'release', "$entry->{distribution}.json");
        if (-f $path) {
            return select_stable_release($entry, read_json($path));
        }
    }
    my $query = 'distribution:' . $entry->{distribution};
    $query =~ s/([^A-Za-z0-9_.:-])/sprintf('%%%02X', ord($1))/ge;
    my $response = HTTP::Tiny->new(timeout => 30)->get(
        'https://fastapi.metacpan.org/v1/release/_search?size=100&sort=date:desc&query=' . $query);
    die "MetaCPAN release lookup failed: $response->{status} $response->{reason}\n"
        unless $response->{success};
    return select_stable_release($entry, decode_json($response->{content}));
}

sub select_stable_release {
    my ($entry, $data) = @_;
    my @releases;
    if (ref($data) eq 'HASH' && ref($data->{releases}) eq 'ARRAY') {
        @releases = @{$data->{releases}};
    } elsif (ref($data) eq 'HASH' && ref($data->{hits}{hits}) eq 'ARRAY') {
        @releases = map { $_->{_source} || $_ } @{$data->{hits}{hits}};
    } else {
        @releases = ($data);
    }
    @releases = sort { ($b->{date} || '') cmp ($a->{date} || '') } grep {
        ref($_) eq 'HASH'
            && ($_->{maturity} || '') =~ /\A(?:released|stable)\z/i
            && (!$_->{name} || $_->{name} eq $entry->{distribution})
    } @releases;
    die "MetaCPAN returned no stable release for $entry->{distribution}\n" unless @releases;
    my $release = { %{$releases[0]} };
    $release->{name} ||= $entry->{distribution};
    $release->{archive} ||= "$release->{name}-$release->{version}.tar.gz";
    return $release;
}

sub fetch_archive {
    my ($entry, $release) = @_;
    if ($option{fixture_dir}) {
        my $path = File::Spec->catfile($option{fixture_dir}, 'archive', $release->{archive});
        return $path if -f $path;
    }
    my $url = $release->{download_url} || '';
    die "release download URL is missing\n" unless $url =~ m{\Ahttps://};
    my ($fh, $path) = File::Temp::tempfile('perlonjava-cpan-XXXXXX', TMPDIR => 1, UNLINK => 0);
    close $fh or die "cannot close temporary archive: $!\n";
    my $response = HTTP::Tiny->new(timeout => 120)->mirror($url, $path);
    die "archive download failed: $response->{status} $response->{reason}\n"
        unless $response->{success};
    return $path;
}

sub check_candidate_prerequisites {
    my ($entry, $archive_path, $candidate_version) = @_;
    my $tar = Archive::Tar->new;
    $tar->read($archive_path, 1) or die "cannot read candidate archive\n";
    my ($meta_file) = grep { m{(?:^|/)META\.json\z} } $tar->list_files;
    die "candidate archive does not contain META.json\n" unless $meta_file;
    my $meta = eval { decode_json($tar->get_content($meta_file)) };
    die "candidate META.json is invalid\n" unless $meta && ref($meta) eq 'HASH';
    die "candidate META.json distribution name does not match registry\n"
        unless ($meta->{name} || '') eq $entry->{distribution};
    my $release_version = $meta->{version} || '';
    die "candidate META.json version does not match the selected release\n"
        if length($release_version) && $release_version ne $candidate_version;
    return unless ref($meta->{prereqs}) eq 'HASH';
    my $requires = $meta->{prereqs}{runtime}{requires} || {};
    for my $module (sort keys %$requires) {
        my $provider = $module_by_name{$module} or next;
        my $actual = effective_provider_version($provider);
        next unless defined $actual;
        my $required = $requires->{$module};
        if (version_less_than($actual, $required)) {
            die "$module >= $required required, but bundled provider $provider->{distribution} "
                . "supports $actual; update or port the bundled provider first\n";
        }
    }
}

sub effective_provider_version {
    my ($provider) = @_;
    my $module_path = $provider->{module};
    $module_path =~ s{::}{/}g;
    $module_path .= '.pm';
    if ($option{overlay_dir}) {
        my $overlay = File::Spec->catfile($option{overlay_dir}, $module_path);
        my $version = read_module_version($overlay);
        return $version if defined $version;
    }
    return read_declared_version($provider);
}

sub read_module_version {
    my ($path) = @_;
    open my $fh, '<', $path or return;
    local $/;
    my $source = <$fh>;
    close $fh;
    return $1 if $source =~ /\$VERSION\s*=\s*['"]?([v\d][\w.]*)/s;
    return;
}

sub version_less_than {
    my ($actual, $required) = @_;
    my $less = eval {
        require version;
        version->parse($actual) < version->parse($required);
    };
    return $less if defined $less && !$@;
    $actual =~ s/[^\d.]//g;
    $required =~ s/[^\d.]//g;
    return $actual < $required;
}

sub extract_archive {
    my ($archive_path, $destination, $distribution, $version) = @_;
    my $tar = Archive::Tar->new;
    $tar->read($archive_path, 1) or die "cannot read archive $archive_path\n";
    my $prefix = "$distribution-$version/";
    my @files = $tar->get_files;
    die "archive is empty\n" unless @files;
    for my $member (@files) {
        my $name = $member->full_path;
        die "unsafe archive path '$name'\n"
            if File::Spec->file_name_is_absolute($name)
                || $name =~ m{(?:\A|/)\.\.(?:/|\z)}
                || $name =~ m{\A[A-Za-z]:};
        $name = substr($name, length($prefix)) if index($name, $prefix) == 0;
        next if $member->is_dir;
        die "unsupported archive member '$name'\n" unless $member->is_file;
        my $relative = $name;
        next unless length $relative;
        my $target = File::Spec->catfile($destination, split m{/}, $relative);
        make_path(dirname($target));
        write_file($target, $member->get_content);
    }
}

sub synchronize_candidate {
    my ($entry, $release, $destination) = @_;
    my $mappings = $entry->{files} || [];
    for my $mapping (@$mappings) {
        die "invalid file mapping\n" unless $mapping->{source} && $mapping->{target};
        my $source = File::Spec->catfile($destination, split m{/}, $mapping->{source});
        if (!-e $source) {
            die "declared upstream path $mapping->{source} is missing\n";
        }
        my $target = File::Spec->catfile($destination, 'candidate', $mapping->{target});
        if (-d $source) {
            find({
                wanted => sub {
                    return unless -f $File::Find::name;
                    my $relative = File::Spec->abs2rel($File::Find::name, $source);
                    my $out = File::Spec->catfile($target, $relative);
                    make_path(dirname($out));
                    copy_file($File::Find::name, $out);
                },
                no_chdir => 1,
            }, $source);
        } else {
            make_path(dirname($target));
            copy_file($source, $target);
        }
    }
    return if $entry->{port_mode} eq 'full-import';
    die "unknown port mode '$entry->{port_mode}'\n"
        unless $entry->{port_mode} eq 'rewritten';
}

sub porting_report {
    my ($entry, $release) = @_;
    return join '',
        "CPAN distribution: $entry->{distribution}\n",
        "Candidate release: $release->{version}\n",
        "Port mode: $entry->{port_mode}\n",
        "Policy: ", ($entry->{update_policy} || 'manual review'), "\n",
        "This directory is staged outside the source tree. Review the diff and\n",
        "run the declared PerlOnJava and upstream tests before applying it.\n";
}

sub absolute_from_root {
    my ($path) = @_;
    return File::Spec->rel2abs($path, $option{root});
}

sub absolute_path {
    my ($path) = @_;
    return File::Spec->rel2abs($path, getcwd());
}

sub read_json {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    my $data = <$fh>;
    close $fh or die "Cannot close $path: $!\n";
    return decode_json($data);
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $digest = Digest::SHA->new(256);
    $digest->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $digest->hexdigest;
}

sub write_file {
    my ($path, $content) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!\n";
    print {$fh} $content or die "Cannot write $path: $!\n";
    close $fh or die "Cannot close $path: $!\n";
}

sub copy_file {
    my ($source, $destination) = @_;
    open my $in, '<:raw', $source or die "Cannot read $source: $!\n";
    local $/;
    my $content = <$in>;
    close $in or die "Cannot close $source: $!\n";
    write_file($destination, $content);
}
