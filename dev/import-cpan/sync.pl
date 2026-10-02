#!/usr/bin/env perl

use strict;
use warnings;

use Archive::Tar;
use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(basename dirname);
use File::Find qw(find);
use File::Path qw(make_path remove_tree);
use File::Spec;
use File::Temp qw(tempfile);
use Getopt::Long qw(GetOptions);
use HTTP::Tiny;
use JSON::PP qw(decode_json);
use version ();

my $script_dir = abs_path(dirname(__FILE__));
my $default_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
my %option = (
    root       => $default_root,
    registry   => File::Spec->catfile($script_dir, 'registry.json'),
    timeout    => 30,
    max_archive_bytes => 100 * 1024 * 1024,
    max_extracted_bytes => 500 * 1024 * 1024,
);
my (@modules, @overlay_dirs);
my $help;
GetOptions(
    'root=s'          => \$option{root},
    'registry=s'      => \$option{registry},
    'module=s@'       => \@modules,
    'check'           => \$option{check},
    'update'          => \$option{update},
    'apply'           => \$option{apply},
    'fixture-dir=s'   => \$option{fixture_dir},
    'stage-dir=s'     => \$option{stage_dir},
    'overlay-dir=s@'  => \@overlay_dirs,
    'timeout=i'       => \$option{timeout},
    'help'            => \$help,
) or usage(2);
usage(0) if $help;

die "--check and --update are mutually exclusive\n"
    if $option{check} && $option{update};
die "--apply requires --update\n" if $option{apply} && !$option{update};
die "--apply is unavailable for rewritten providers; review a staged port instead\n"
    if $option{apply};
die "--timeout must be positive\n" unless $option{timeout} > 0;

$option{root} = abs_path($option{root})
    or die "Cannot resolve repository root '$option{root}'\n";
$option{registry} = abs_path($option{registry})
    or die "Cannot resolve registry '$option{registry}'\n";
for (@overlay_dirs) {
    $_ = abs_path($_) // die "Cannot resolve provider overlay '$_'\n";
}

my $registry = read_json($option{registry});
die "CPAN registry schema_version must be 1\n"
    unless ($registry->{schema_version} // 0) == 1;
die "CPAN registry modules must be an array\n"
    unless ref($registry->{modules}) eq 'ARRAY';

my @selected = select_modules($registry->{modules}, \@modules);
my $check_errors = check_registry(\@selected, $option{root});
if (@$check_errors) {
    print STDERR "Bundled CPAN registry check failed:\n";
    print STDERR "  - $_\n" for @$check_errors;
    exit 1;
}

if ($option{check}) {
    print "Bundled CPAN provider contracts are consistent (" . scalar(@selected)
        . " distributions).\n";
    exit 0;
}

if ($option{update}) {
    my $provider_map = build_provider_map($registry->{modules}, $option{root});
    my $failed = 0;
    for my $module (@selected) {
        my $ok = stage_latest($module, $provider_map);
        $failed ||= !$ok;
    }
    exit($failed ? 1 : 0);
}

print "Bundled CPAN module report (dry-run; no files will be changed):\n";
for my $module (@selected) {
    printf "  %-24s upstream=%-8s mode=%s\n",
        $module->{distribution}, $module->{upstream_distribution_version},
        $module->{port_mode};
    for my $provider (@{$module->{providers}}) {
        printf "    %-30s %-8s %s\n",
            $provider->{module} // $provider->{provider},
            $provider->{version}, $provider->{contract};
    }
    print "    action: run with UPDATE=1 to discover and stage the latest stable release\n";
}
exit 0;

sub check_registry {
    my ($modules, $root) = @_;
    my @errors;
    my %distribution_seen;
    my %module_providers;
    for my $module (@$modules) {
        my $distribution = $module->{distribution} // '';
        if (!$distribution || $distribution_seen{$distribution}++) {
            push @errors, "missing or duplicate distribution '$distribution'";
            next;
        }
        push @errors, "$distribution has an invalid upstream version"
            unless valid_version($module->{upstream_distribution_version});
        push @errors, "$distribution must use port_mode rewritten"
            unless ($module->{port_mode} // '') eq 'rewritten';
        push @errors, "$distribution has an invalid update_policy"
            unless ($module->{update_policy} // '') eq 'stage-and-manual-port';
        push @errors, "$distribution source must use HTTPS"
            unless ($module->{source} // '') =~ m{\Ahttps://};
        push @errors, "$distribution providers must be an array"
            unless ref($module->{providers}) eq 'ARRAY';
        push @errors, "$distribution tests must be an array"
            unless ref($module->{tests}) eq 'ARRAY';

        for my $provider (@{$module->{providers} || []}) {
            my $path = $provider->{path} // '';
            if (!safe_repo_path($path) || !-f File::Spec->catfile($root, split m{/}, $path)) {
                push @errors, "$distribution provider path is missing or unsafe: $path";
                next;
            }
            my $actual = read_provider_version($provider,
                File::Spec->catfile($root, split m{/}, $path));
            if (!defined $actual) {
                push @errors, "$distribution cannot read provider version from $path";
                next;
            }
            if (!same_version($actual, $provider->{version})) {
                push @errors, "$distribution $path declares $actual; registry pins $provider->{version}";
            }
            if ($provider->{same_version_as_upstream}
                    && !same_version($actual, $module->{upstream_distribution_version})) {
                push @errors, "$distribution $path version $actual differs from upstream "
                    . $module->{upstream_distribution_version};
            }
            if (($provider->{kind} // '') eq 'perl_version') {
                my $package = $provider->{module} // '';
                if ($package && $module_providers{$package}++) {
                    push @errors, "duplicate provider registration for $package";
                }
            }
        }
        for my $test (@{$module->{tests} || []}) {
            push @errors, "$distribution test file is missing or unsafe: $test"
                unless safe_repo_path($test) && -f File::Spec->catfile($root, split m{/}, $test);
        }
    }
    return \@errors;
}

sub select_modules {
    my ($all, $requested) = @_;
    my @filters = map { split /,/ } @$requested;
    @filters = grep { length } @filters;
    my @selected = grep {
        my $entry = $_;
        !@filters || grep {
            my $filter = $_;
            index($entry->{distribution} // '', $filter) >= 0
                || grep { index($_->{module} // '', $filter) >= 0 }
                    @{$entry->{providers} || []}
        } @filters
    } @$all;
    die "No registry module matches selection " . join(', ', @filters) . "\n"
        if @filters && !@selected;
    return @selected;
}

sub read_provider_version {
    my ($provider, $path) = @_;
    my $text = read_raw($path);
    my $kind = $provider->{kind} // '';
    if ($kind eq 'perl_version') {
        return $2 if $text =~ /\$VERSION\s*=\s*(['"])([^'"\n]+)\1/s;
        return;
    }
    if ($kind eq 'java_static_string') {
        return $1 if $text =~ /\b\Q$provider->{symbol}\E\s*=\s*"([^"]+)"/;
        return;
    }
    if ($kind eq 'java_perl_global') {
        return $1 if $text =~ /\Q$provider->{symbol}\E[\s\S]{0,180}?new RuntimeScalar\("([^"]+)"\)/;
        return;
    }
    if ($kind eq 'gradle_catalog') {
        return $1 if $text =~ /^\Q$provider->{symbol}\E\s*=\s*"([^"]+)"\s*$/m;
        return;
    }
    return;
}

sub build_provider_map {
    my ($modules, $root) = @_;
    my %map;
    for my $module (@$modules) {
        for my $provider (@{$module->{providers} || []}) {
            next unless ($provider->{kind} // '') eq 'perl_version';
            my $path = $provider->{path};
            $path =~ s{\A(?:src/main/perl/lib/)?}{};
            $map{$provider->{module}} = {
                version => $provider->{version},
                path    => $path,
                owner   => $module->{distribution},
            };
        }
    }
    return \%map;
}

sub stage_latest {
    my ($module, $provider_map) = @_;
    my $distribution = $module->{distribution};
    my $release = fetch_release($distribution);
    unless ($release) {
        warn "$distribution: cannot retrieve latest stable release metadata\n";
        return 0;
    }
    my $latest = $release->{version};
    unless (valid_version($latest) && defined($release->{checksum_sha256})
            && $release->{checksum_sha256} =~ /\A[0-9a-f]{64}\z/i) {
        warn "$distribution: release metadata lacks a stable version or SHA-256 checksum\n";
        return 0;
    }
    unless (version->parse($latest) > version->parse($module->{upstream_distribution_version})) {
        print "$distribution $latest is not newer than the recorded upstream version ",
            $module->{upstream_distribution_version}, "; nothing to stage.\n";
        return 1;
    }

    my $archive = fetch_archive($release);
    unless ($archive) {
        warn "$distribution $latest: archive download failed\n";
        return 0;
    }
    my $actual_sha = sha256_file($archive);
    unlink $archive if !$option{fixture_dir};
    unless (lc($actual_sha) eq lc($release->{checksum_sha256})) {
        warn "$distribution $latest: archive SHA-256 mismatch; no files were staged\n";
        return 0;
    }

    my $stage_root = $option{stage_dir}
        ? File::Spec->rel2abs($option{stage_dir})
        : File::Spec->catdir(File::Spec->tmpdir, 'perlonjava-cpan-staging');
    my $stage = File::Spec->catdir($stage_root, "$distribution-$latest");
    if (-e $stage) {
        warn "$distribution $latest: staging path already exists: $stage\n";
        return 0;
    }
    make_path($stage);
    my $ok = eval { safe_extract_archive($archive, $stage); 1 };
    if (!$ok) {
        my $error = $@ || 'unknown extraction failure';
        remove_tree($stage);
        warn "$distribution $latest: $error";
        return 0;
    }

    my $meta_path = find_meta_json($stage);
    unless ($meta_path) {
        remove_tree($stage);
        warn "$distribution $latest: staged archive has no META.json\n";
        return 0;
    }
    my $meta = eval { read_json($meta_path) };
    if (!$meta || ($meta->{name} // '') ne $distribution
            || !valid_version($meta->{version})
            || !same_version($meta->{version}, $latest)) {
        remove_tree($stage);
        warn "$distribution $latest: staged META.json identity/version does not match release metadata\n";
        return 0;
    }
    my $prereq_error = check_prerequisites($module, $meta, $provider_map, $stage);
    if ($prereq_error) {
        remove_tree($stage);
        warn "$prereq_error\n";
        return 0;
    }

    write_porting_report($module, $meta, $stage, $actual_sha);

    print "$distribution $latest verified and staged at $stage\n";
    print "  SHA-256: $actual_sha\n";
    print "  Source/API/test changes require manual porting; tracked files were not changed.\n";
    return 1;
}

sub fetch_release {
    my ($distribution) = @_;
    if ($option{fixture_dir}) {
        my $path = File::Spec->catfile($option{fixture_dir}, 'release', "$distribution.json");
        return unless -f $path;
        my $release = eval { read_json($path) };
        return unless ref($release) eq 'HASH' && ($release->{maturity} // '') eq 'released'
            && $release->{authorized};
        return $release;
    }
    my $url = 'https://fastapi.metacpan.org/v1/release/' . url_escape($distribution);
    my $response = http_client()->get($url);
    return unless $response->{success}
        && ($response->{url} // $url) =~ m{\Ahttps://fastapi\.metacpan\.org/};
    my $release = eval { decode_json($response->{content}) };
    return unless ref($release) eq 'HASH' && ($release->{maturity} // '') eq 'released'
        && $release->{authorized};
    return $release;
}

sub fetch_archive {
    my ($release) = @_;
    my $url = $release->{download_url} // '';
    return unless $url =~ m{\Ahttps://cpan\.metacpan\.org/[-A-Za-z0-9_./]+\.tar\.gz\z};
    my $name = basename($url);
    my $archive;
    if ($option{fixture_dir}) {
        $archive = File::Spec->catfile($option{fixture_dir}, 'archive', $name);
        return unless -f $archive;
        return if -s $archive > $option{max_archive_bytes};
        return $archive;
    }
    my $response = http_client()->get($url);
    return unless $response->{success}
        && ($response->{url} // $url) =~ m{\Ahttps://cpan\.metacpan\.org/}
        && length($response->{content}) <= $option{max_archive_bytes};
    my ($fh, $path) = tempfile('perlonjava-cpan-XXXXXX', TMPDIR => 1, UNLINK => 0);
    binmode $fh;
    print {$fh} $response->{content} or die "Cannot write downloaded archive: $!\n";
    close $fh or die "Cannot close downloaded archive: $!\n";
    return $path;
}

sub check_prerequisites {
    my ($module, $meta, $provider_map, $stage) = @_;
    my %requires;
    for my $phase (qw(runtime build configure test)) {
        my $requirements = $meta->{prereqs}{$phase}{requires};
        next unless ref($requirements) eq 'HASH';
        for my $package (keys %$requirements) {
            my $candidate = $requirements->{$package};
            if (!exists($requires{$package}) || !valid_version($requires{$package})
                    || (valid_version($candidate)
                        && version->parse($candidate) > version->parse($requires{$package}))) {
                $requires{$package} = $candidate;
            }
        }
    }
    if (ref($meta->{requires}) eq 'HASH') {
        for my $package (keys %{$meta->{requires}}) {
            my $candidate = $meta->{requires}{$package};
            if (!exists($requires{$package}) || !valid_version($requires{$package})
                    || (valid_version($candidate)
                        && version->parse($candidate) > version->parse($requires{$package}))) {
                $requires{$package} = $candidate;
            }
        }
    }
    for my $package (sort keys %requires) {
        my $minimum = $requires{$package};
        next unless defined($minimum) && length($minimum) && $minimum ne '0';
        my $provider = $provider_map->{$package} or next;
        return "$module->{distribution} $meta->{version} has an invalid minimum version "
            . "'$minimum' for registered provider $package"
            unless valid_version($minimum);
        my ($actual, $origin) = effective_provider_version($package, $provider);
        return "$module->{distribution} $meta->{version} requires $package >= $minimum, "
            . "but no readable effective provider version was found; update/port $provider->{owner}"
            unless defined $actual;
        next if version->parse($actual) >= version->parse($minimum);
        my $action = $provider->{owner} eq 'Object-Pad'
            ? 'port a newer Object::Pad shim or configure an external provider'
            : "update/port $provider->{owner}";
        return "$module->{distribution} $meta->{version} requires $package >= $minimum, "
            . "but the effective bundled provider from $origin is $actual; $action before running consumer tests";
    }
    return;
}

sub write_porting_report {
    my ($module, $meta, $stage, $sha) = @_;
    my $path = File::Spec->catfile($stage, 'PERLONJAVA-PORTING.txt');
    open my $fh, '>', $path or die "Cannot write $path: $!\n";
    print {$fh} "Distribution: $module->{distribution}\n";
    print {$fh} "Version: $meta->{version}\n";
    print {$fh} "Archive SHA-256: $sha\n";
    print {$fh} "Port mode: $module->{port_mode}\n";
    print {$fh} "Policy: $module->{update_policy}\n\n";
    print {$fh} "Tracked providers to review:\n";
    for my $provider (@{$module->{providers} || []}) {
        print {$fh} "  $provider->{path} ($provider->{contract}; registry $provider->{version})\n";
    }
    print {$fh} "\nMETA.json prerequisites:\n";
    for my $phase (qw(runtime build configure test)) {
        my $requirements = $meta->{prereqs}{$phase}{requires};
        next unless ref($requirements) eq 'HASH' && %$requirements;
        print {$fh} "  $phase:\n";
        print {$fh} "    $_ >= $requirements->{$_}\n" for sort keys %$requirements;
    }
    print {$fh} "\nReview upstream lib/, t/, META.json, API changes, and test mappings.\n";
    print {$fh} "This stage is evidence only; no tracked file was modified.\n";
    close $fh or die "Cannot close $path: $!\n";
}

sub effective_provider_version {
    my ($package, $provider) = @_;
    (my $relative = $package) =~ s{::}{/}g;
    $relative .= '.pm';
    for my $overlay (@overlay_dirs) {
        my $path = File::Spec->catfile($overlay, split m{/}, $relative);
        next unless -f $path;
        my $actual = parse_perl_module_version($path);
        return ($actual, $path) if defined $actual;
    }
    my $path = File::Spec->catfile($option{root}, 'src', 'main', 'perl', 'lib', split m{/}, $relative);
    my $actual = -f $path ? parse_perl_module_version($path) : undef;
    return ($actual, $provider->{owner} . ' (' . $path . ')') if defined $actual;
    return;
}

sub parse_perl_module_version {
    my ($path) = @_;
    my $text = read_raw($path);
    return $2 if $text =~ /\$VERSION\s*=\s*(['"])([^'"\n]+)\1/s;
    return;
}

sub safe_extract_archive {
    my ($archive_path, $destination) = @_;
    my $tar = Archive::Tar->new;
    $tar->read($archive_path, 1)
        or die "cannot read archive: " . $tar->error . "\n";
    my $expanded_bytes = 0;
    my @entries = $tar->get_files;
    die "archive contains too many entries\n" if @entries > 100_000;
    for my $entry (@entries) {
        my $name = $entry->full_path;
        die "unsafe archive path '$name'\n" unless safe_archive_path($name);
        die "archive contains a non-file entry '$name'\n"
            unless $entry->is_file || $entry->is_dir;
        my @parts = split m{/}, $name;
        my $target = File::Spec->catfile($destination, @parts);
        if ($entry->is_dir) {
            make_path($target);
            next;
        }
        $expanded_bytes += $entry->size;
        die "archive exceeds expanded-size limit\n"
            if $expanded_bytes > $option{max_extracted_bytes};
        make_path(dirname($target));
        open my $out, '>:raw', $target or die "cannot stage $name: $!\n";
        print {$out} $entry->get_content or die "cannot write staged file $name: $!\n";
        close $out or die "cannot close staged file $name: $!\n";
        my $mode = $entry->mode & 0777;
        chmod(0644 | ($mode & 0111), $target)
            or die "cannot set staged mode for $name: $!\n";
    }
}

sub find_meta_json {
    my ($stage) = @_;
    my @paths;
    find({
        wanted => sub {
            push @paths, $File::Find::name
                if basename($File::Find::name) eq 'META.json' && -f $File::Find::name;
        },
        no_chdir => 1,
    }, $stage);
    return $paths[0] if @paths == 1;
    return;
}

sub read_json {
    my ($path) = @_;
    my $raw = read_raw($path);
    my $value = eval { decode_json($raw) };
    die "Invalid JSON in $path: $@\n" if $@;
    return $value;
}

sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    local $/;
    return <$fh>;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $sha = Digest::SHA->new(256);
    $sha->addfile($fh);
    return $sha->hexdigest;
}

sub http_client {
    return HTTP::Tiny->new(
        timeout => $option{timeout},
        agent => 'PerlOnJava-bundled-cpan-sync/1.0',
        max_redirect => 3,
    );
}

sub valid_version {
    my ($value) = @_;
    return 0 unless defined($value) && !ref($value) && $value =~ /\A[0-9v][0-9A-Za-z._+-]*\z/;
    return eval { version->parse($value); 1 } ? 1 : 0;
}

sub same_version {
    my ($left, $right) = @_;
    return 0 unless valid_version($left) && valid_version($right);
    return version->parse($left) == version->parse($right);
}

sub safe_repo_path {
    my ($path) = @_;
    return 0 unless defined($path) && length($path)
        && $path !~ m{\A/} && $path !~ m{\\|:}
        && !grep { $_ eq '..' || $_ eq '.' || $_ eq '' } split m{/}, $path;
    return 1;
}

sub safe_archive_path {
    my ($path) = @_;
    return 0 unless defined($path) && length($path)
        && $path !~ m{\A/} && $path !~ m{[\\:\x00-\x1f\x7f]}
        && !grep { $_ eq '..' || $_ eq '.' } split m{/}, $path;
    return 1;
}

sub url_escape {
    my ($value) = @_;
    $value =~ s/([^A-Za-z0-9_.-])/sprintf('%%%02X', ord($1))/eg;
    return $value;
}

sub usage {
    my ($exit) = @_;
    print <<'USAGE';
Usage: perl dev/import-cpan/sync.pl [options]

Default: print a dry-run report; do not download or change files.
  --check                  verify pinned provider/version contracts offline
  --update                 discover a stable release, verify it, and stage it
  --module NAME            select a distribution/package (repeatable, or CSV)
  --overlay-dir DIR        consider an effective provider overlay before bundled lib
  --apply                  rejected for rewritten providers; review/port staged source
  --root DIR               repository root (fixture testing)
  --registry FILE          provider registry (fixture testing)
  --fixture-dir DIR        offline release/archive fixtures (fixture testing)
  --stage-dir DIR          staging root (default: system temporary directory)
  --help                   show this help

`--update` never installs globally or overwrites tracked files. It checks
release identity and SHA-256, validates archive paths, checks META.json
prerequisites against effective bundled/overlay providers, and stages a source
archive for manual porting. Rewritten providers are never auto-copied.
USAGE
    exit $exit;
}
