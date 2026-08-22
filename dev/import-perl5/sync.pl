#!/usr/bin/env perl
# Imports files from the perl5/ tree into this repository and optionally applies
# patches from dev/import-perl5/patches/ (patch -p0). This is separate from
# CPAN tarball patches under src/main/perl/lib/PerlOnJava/CpanPatches/; see
# dev/design/patch-and-cpan-prefs-layout.md.
#
# By default this processes EVERY row in config.yaml (bulk refresh against perl5/).
# To add or refresh a single module without touching unrelated trees:
#   perl dev/import-perl5/sync.pl --only File-DosGlob
#   perl dev/import-perl5/sync.pl --only src/main/perl/lib/File/DosGlob.pm
#
# Options: --help, --only SUBSTRING (matches source: or target: field),
#          --verify-idempotent
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Copy qw(copy);
use File::Find qw(find);
use File::Spec;
use File::Temp qw(tempdir tempfile);
use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);

# Simple YAML parser for our specific needs
sub parse_yaml {
    my ($file) = @_;
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    
    my @imports;
    my $current_import;
    
    while (my $line = <$fh>) {
        chomp $line;
        
        # Skip comments and empty lines
        next if $line =~ /^\s*#/ || $line =~ /^\s*$/;
        
        # Start of new import
        if ($line =~ /^\s*-\s+source:\s*(.+)/) {
            push @imports, $current_import if $current_import;
            $current_import = { source => $1 };
        }
        elsif ($current_import) {
            if ($line =~ /^\s+target:\s*(.+)/) {
                $current_import->{target} = $1;
            }
            elsif ($line =~ /^\s+patch:\s*(.+)/) {
                $current_import->{patch} = $1 unless $1 eq 'null';
            }
            elsif ($line =~ /^\s+type:\s*(.+)/) {
                $current_import->{type} = $1;
            }
            elsif ($line =~ /^\s+protected:\s*(.+)/) {
                $current_import->{protected} = ($1 =~ /true|yes|1/i) ? 1 : 0;
            }
            elsif ($line =~ /^\s+exclude:\s*$/) {
                # Start of exclude list
                $current_import->{exclude} = [];
            }
            elsif ($line =~ /^\s+-\s+(.+)/ && $current_import->{exclude}) {
                # Exclude list item
                push @{$current_import->{exclude}}, $1;
            }
        }
    }
    push @imports, $current_import if $current_import;
    close $fh;
    
    return \@imports;
}

# True if $path is $dir or a proper child (same path or $dir/...). Avoids the
# false positive where index($path, $dir)==0 matches siblings like
# perl5_t/ticket when $dir is perl5_t/t (prefix without following slash).
sub path_is_under_dir {
    my ($dir, $path) = @_;
    return 0 unless defined $dir && defined $path && length $dir && length $path;
    for ($dir, $path) { s{//+}{/}g; s{/+\z}{} }
    return 1 if $path eq $dir;
    return $path =~ m{^\Q$dir\E/};
}

# Apply a patch file to a target
sub apply_patch {
    my ($target, $patch_file) = @_;
    
    # --no-backup-if-mismatch prevents creating .orig files
    my $cmd = "patch --no-backup-if-mismatch -r - -p0 '$target' < '$patch_file'";
    print "  Applying patch: $patch_file\n";
    
    my $result = system($cmd);
    if ($result != 0) {
        unlink "$target.rej", "$target.orig";
        warn "  Warning: patch failed with exit code $result\n";
        return 0;
    }
    return 1;
}

# Copy a directory recursively using rsync
sub copy_directory {
    my ($source, $target, $project_root, $protected_files, $exclude_patterns) = @_;
    
    # Build rsync command with exclusions for protected files
    my $cmd = "rsync -a";
    
    # Add exclusions for protected files
    if ($protected_files && @$protected_files) {
        for my $protected_path (@$protected_files) {
            # protected_path is relative to project root, need to make it absolute
            my $abs_protected = File::Spec->catfile($project_root, $protected_path);
            
            # Calculate relative path from target directory (strict prefix)
            if (path_is_under_dir($target, $abs_protected)) {
                my $dir = $target;
                my $p = $abs_protected;
                for ($dir, $p) { s{//+}{/}g; s{/+\z}{} }
                next if $p eq $dir;
                my $rel_path = substr($p, length($dir) + 1);
                if ($rel_path) {
                    $cmd .= " --exclude='$rel_path'";
                    print "  Excluding protected file: $rel_path\n";
                }
            }
        }
    }
    
    # Add explicit exclude patterns from config
    if ($exclude_patterns && @$exclude_patterns) {
        for my $pattern (@$exclude_patterns) {
            $cmd .= " --exclude='$pattern'";
            print "  Excluding pattern: $pattern\n";
        }
    }
    
    $cmd .= " '$source/' '$target/'";
    print "  Running: $cmd\n";
    
    my $result = system($cmd);
    if ($result != 0) {
        warn "  Warning: rsync failed with exit code $result\n";
        return 0;
    }
    return 1;
}

# Split the canonical generated corpus without dropping or reordering a byte of
# any TESTCHUNK section. The small dispatcher retains the canonical lexical
# helper scope and evals only the selected section. Leading newlines in each
# chunk preserve canonical caller line numbers in TAP diagnostics.
sub split_unicode_testprop {
    my ($canonical) = @_;
    my @markers;
    while ($canonical
           =~ /^if \(!\$::TESTCHUNK or \$::TESTCHUNK == (\d+)\) \{\s*$/mg)
    {
        push @markers, { number => 0 + $1, start => $-[0] };
    }
    unless (join(',', map { $_->{number} } @markers) eq join(',', 1 .. 10)) {
        die "Generated TestProp.pl has unexpected TESTCHUNK order: "
            . (@markers ? join(',', map { $_->{number} } @markers) : '(none)')
            . "\n";
    }

    pos($canonical) = $markers[-1]{start};
    my $finished_start;
    if ($canonical =~ /^Finished\(\);\s*$/mg) {
        $finished_start = $-[0];
    }
    die "Generated TestProp.pl is missing Finished() after TESTCHUNK 10\n"
        unless defined $finished_start;

    my $preamble = substr($canonical, 0, $markers[0]{start});
    my $finish = substr($canonical, $finished_start);
    my @sections;
    for my $index (0 .. $#markers) {
        my $end = $index == $#markers
            ? $finished_start
            : $markers[$index + 1]{start};
        my $section = substr($canonical, $markers[$index]{start},
                             $end - $markers[$index]{start});
        my $line_offset = substr($canonical, 0, $markers[$index]{start})
            =~ tr/\n//;
        my %counts;
        $counts{$1}++ while $section
            =~ /^\s+(Expect|Error|Test_GCB|Test_SB|Test_LB|Test_WB)\(/mg;
        push @sections, {
            number      => $markers[$index]{number},
            source      => $section,
            padded      => ("\n" x $line_offset) . $section,
            line_offset => $line_offset,
            counts      => \%counts,
        };
    }

    my $reconstructed = $preamble . join('', map { $_->{source} } @sections)
        . $finish;
    die "Internal TestProp.pl split changed canonical content\n"
        unless $reconstructed eq $canonical;

    my $canonical_sha = sha256_hex($canonical);
    my @manifest = (
        "\n# PerlOnJava lossless TESTCHUNK dispatcher\n",
        "# Canonical pinned mktables SHA-256: $canonical_sha\n",
        "# Canonical current-upstream mktables SHA-256: $canonical_sha\n",
    );
    for my $section (@sections) {
        my @counts = map { "$_=$section->{counts}{$_}" }
            sort keys %{$section->{counts}};
        push @manifest, "# TESTCHUNK $section->{number}: "
            . join(', ', @counts) . "\n";
    }
    push @manifest, <<'DISPATCHER';
my $__poj_testprop_dir = __FILE__;
$__poj_testprop_dir =~ s{[^/\\]+\z}{};
my @__poj_testprop_chunks = !$::TESTCHUNK
    ? (1 .. 10)
    : ($::TESTCHUNK >= 1 && $::TESTCHUNK <= 10 ? ($::TESTCHUNK) : ());
for my $__poj_testprop_chunk (@__poj_testprop_chunks) {
    my $__poj_testprop_path = sprintf '%sTestProp-%02d.pl',
        $__poj_testprop_dir, $__poj_testprop_chunk;
    open my $__poj_testprop_fh, '<', $__poj_testprop_path
        or die "Cannot load $__poj_testprop_path: $!\n";
    local $/;
    my $__poj_testprop_source = <$__poj_testprop_fh>;
    close $__poj_testprop_fh
        or die "Cannot close $__poj_testprop_path: $!\n";
    my $__poj_testprop_completed = eval($__poj_testprop_source . "\n; 1;");
    my $__poj_testprop_error = $@;
    unless ($__poj_testprop_completed) {
        $__poj_testprop_error = "section eval returned false without an error\n"
            unless length $__poj_testprop_error;
        die "Cannot evaluate $__poj_testprop_path: $__poj_testprop_error";
    }
}
DISPATCHER
    my $dispatcher = $preamble . join('', @manifest) . $finish;
    return ($dispatcher, \@sections, $canonical_sha);
}

# Generate the Unicode property test fixture from a pristine copy of the
# current upstream source data. Upstream deliberately does not commit TestProp.pl; its
# normal build creates it with lib/unicore/mktables -maketest.
sub generate_unicode_testprop {
    my ($generator_relative, $target, $project_root) = @_;
    my $generator = File::Spec->catfile($project_root, $generator_relative);
    my $unicode_source = dirname($generator);
    my @required = (
        $generator,
        File::Spec->catfile($unicode_source, 'version'),
        File::Spec->catfile($unicode_source, 'UnicodeData.txt'),
    );
    for my $required (@required) {
        unless (-f $required) {
            warn "  ERROR: Cannot generate Unicode TestProp.pl; missing pinned generation prerequisite "
                . "(compatibility alias; selected source is the current checkout, not a historical SHA): $required\n"
                . "  Restore perl5/lib/unicore from the current upstream source "
                . "before running this sync.\n\n";
            return 0;
        }
    }

    my $temporary = tempdir('perlonjava-testprop-XXXXXX', TMPDIR => 1, CLEANUP => 1);
    my $temporary_unicode = File::Spec->catdir($temporary, 'unicore');
    make_path($temporary_unicode) or do {
        warn "  ERROR: Cannot create temporary Unicode generation directory: $!\n\n";
        return 0;
    };
    unless (copy_directory($unicode_source, $temporary_unicode,
                           $project_root, [], [])) {
        warn "  ERROR: Cannot copy current upstream Unicode data for TestProp.pl generation.\n\n";
        return 0;
    }

    my $generated = File::Spec->catfile($temporary_unicode, 'TestProp.pl');
    my $original_dir = getcwd();
    unless (chdir $project_root) {
        warn "  ERROR: Cannot enter project root for TestProp.pl generation: $!\n\n";
        return 0;
    }
    print "  Generating with current upstream Unicode data: $generator_relative\n";
    my $result = system($^X, $generator_relative,
                        '-C', $temporary_unicode,
                        '-T', $generated,
                        '-q');
    my $generation_error = $!;
    unless (chdir $original_dir) {
        die "sync.pl: cannot restore working directory '$original_dir': $!\n";
    }
    if ($result != 0) {
        my $exit = $result == -1
            ? "could not start: $generation_error"
            : ($result & 127)
                ? "terminated by signal " . ($result & 127)
                : "exit code " . ($result >> 8);
        warn "  ERROR: Unicode TestProp.pl generation failed ($exit).\n"
            . "  Ensure the current upstream perl5/lib/unicore data is complete and the "
            . "host Perl can run mktables.\n\n";
        return 0;
    }
    unless (-s $generated) {
        warn "  ERROR: Unicode generator completed without producing TestProp.pl.\n\n";
        return 0;
    }

    open my $generated_fh, '<', $generated or do {
        warn "  ERROR: Cannot inspect generated TestProp.pl: $!\n\n";
        return 0;
    };
    local $/;
    my $contents = <$generated_fh>;
    close $generated_fh;
    my ($dispatcher, $sections, $canonical_sha);
    eval {
        ($dispatcher, $sections, $canonical_sha)
            = split_unicode_testprop($contents);
        1;
    } or do {
        my $error = $@ || 'unknown split error';
        chomp $error;
        warn "  ERROR: Cannot split generated TestProp.pl: $error\n\n";
        return 0;
    };

    my $target_dir = dirname($target);
    unless (-d $target_dir) {
        make_path($target_dir) or do {
            warn "  ERROR: Cannot create generated fixture directory: $!\n\n";
            return 0;
        };
    }
    my @outputs;
    my ($volume, $directories, $base) = File::Spec->splitpath($target);
    $base =~ s/\.pl\z//;
    for my $section (@$sections) {
        my $chunk_base = sprintf '%s-%02d.pl', $base, $section->{number};
        my $chunk_target = File::Spec->catpath($volume, $directories,
                                               $chunk_base);
        push @outputs, [$chunk_target, $section->{padded}];
    }
    # Publish the dispatcher last so readers never see it before all ten chunk
    # names exist. Stage every output completely before replacing any member of
    # the current family; a write failure therefore leaves that family intact.
    push @outputs, [$target, $dispatcher];
    my @staged;
    for my $output (@outputs) {
        my ($path, $source) = @$output;
        my ($output_fh, $staged_path);
        eval {
            ($output_fh, $staged_path) = tempfile(
                '.TestProp-sync-XXXXXX', DIR => $target_dir, UNLINK => 0
            );
            1;
        } or do {
            my $error = $@ || 'unknown temporary-file error';
            chomp $error;
            warn "  ERROR: Cannot stage generated fixture $path: $error\n\n";
            unlink $_->[0] for @staged;
            return 0;
        };
        print {$output_fh} $source or do {
            warn "  ERROR: Cannot stage generated fixture $path: $!\n\n";
            close $output_fh;
            unlink $staged_path;
            unlink $_->[0] for @staged;
            return 0;
        };
        close $output_fh or do {
            warn "  ERROR: Cannot close staged generated fixture $path: $!\n\n";
            unlink $staged_path;
            unlink $_->[0] for @staged;
            return 0;
        };
        push @staged, [$staged_path, $path];
    }
    while (my $staged = shift @staged) {
        my ($staged_path, $path) = @$staged;
        unless (rename $staged_path, $path) {
            warn "  ERROR: Cannot publish generated fixture $path: $!\n\n";
            unlink $staged_path;
            unlink $_->[0] for @staged;
            return 0;
        }
    }
    print "  Installed lossless generated fixture dispatcher and 10 chunks: "
        . File::Spec->abs2rel($target, $project_root)
        . " (canonical SHA-256 $canonical_sha)\n";
    return 1;
}

# Generate the core Name.pl table in the checked-out perl5 source before its
# ordinary manifest row copies it into the bundled library. Upstream does not
# commit this build product, but _charnames.pm loads it as a normal core file.
sub generate_unicode_name_source {
    my ($generator_relative, $target, $project_root) = @_;
    if ($^V lt v5.36.0) {
        warn "  ERROR: Cannot generate Unicode Name.pl with $^X ($^V).\n"
            . "  Current perl5/lib/unicore/mktables uses builtin, which requires "
            . "Perl 5.36 or newer. Re-run sync.pl with a compatible host Perl.\n\n";
        return 0;
    }

    my $generator = File::Spec->catfile($project_root, $generator_relative);
    my $unicode_source = dirname($generator);
    for my $required ($generator,
                      File::Spec->catfile($unicode_source, 'version'),
                      File::Spec->catfile($unicode_source, 'UnicodeData.txt')) {
        unless (-f $required) {
            warn "  ERROR: Cannot generate Unicode Name.pl; missing checkout "
                . "generation prerequisite: $required\n\n";
            return 0;
        }
    }

    my $temporary = tempdir('perlonjava-namepl-XXXXXX', TMPDIR => 1, CLEANUP => 1);
    my $temporary_unicode = File::Spec->catdir($temporary, 'unicore');
    make_path($temporary_unicode) or do {
        warn "  ERROR: Cannot create temporary Unicode generation directory: $!\n\n";
        return 0;
    };
    unless (copy_directory($unicode_source, $temporary_unicode,
                           $project_root, [], [])) {
        warn "  ERROR: Cannot copy checked-out Unicode data for Name.pl generation.\n\n";
        return 0;
    }

    my $original_dir = getcwd();
    unless (chdir $project_root) {
        warn "  ERROR: Cannot enter project root for Name.pl generation: $!\n\n";
        return 0;
    }
    print "  Generating Unicode Name.pl with $^X: $generator_relative\n";
    my $result = system($^X, $generator_relative, '-C', $temporary_unicode, '-q');
    my $generation_error = $!;
    unless (chdir $original_dir) {
        die "sync.pl: cannot restore working directory '$original_dir': $!\n";
    }
    if ($result != 0) {
        my $exit = $result == -1
            ? "could not start: $generation_error"
            : ($result & 127)
                ? "terminated by signal " . ($result & 127)
                : "exit code " . ($result >> 8);
        warn "  ERROR: Unicode Name.pl generation failed ($exit).\n"
            . "  Re-run sync.pl with a host Perl that can run the current "
            . "perl5/lib/unicore/mktables.\n\n";
        return 0;
    }

    my $generated = File::Spec->catfile($temporary_unicode, 'Name.pl');
    unless (-s $generated) {
        warn "  ERROR: Unicode generator completed without producing Name.pl.\n\n";
        return 0;
    }
    open my $generated_fh, '<', $generated or do {
        warn "  ERROR: Cannot inspect generated Name.pl: $!\n\n";
        return 0;
    };
    binmode $generated_fh;
    local $/;
    my $contents = <$generated_fh>;
    close $generated_fh;
    my $actual_sha = sha256_hex($contents);
    my $target_dir = dirname($target);
    make_path($target_dir) unless -d $target_dir;
    my ($target_fh, $staged) = tempfile('.Name-sync-XXXXXX', DIR => $target_dir,
                                        UNLINK => 0);
    binmode $target_fh;
    print {$target_fh} $contents or do {
        warn "  ERROR: Cannot stage generated Name.pl: $!\n\n";
        close $target_fh;
        unlink $staged;
        return 0;
    };
    close $target_fh or do {
        warn "  ERROR: Cannot close staged Name.pl: $!\n\n";
        unlink $staged;
        return 0;
    };
    rename $staged, $target or do {
        warn "  ERROR: Cannot publish generated Name.pl: $!\n\n";
        unlink $staged;
        return 0;
    };
    print "  Generated Name.pl (SHA-256 $actual_sha)\n";
    return 1;
}

sub usage {
    print <<'USAGE';
PerlOnJava perl5 import sync — see dev/import-perl5/config.yaml

  perl dev/import-perl5/sync.pl
      Refresh every import in config.yaml (full manifest replay against perl5/).
      Use when intentionally updating the bundled perl5 snapshot.

  perl dev/import-perl5/sync.pl --only SUBSTRING
      Refresh only imports whose source: or target: contains SUBSTRING (substring match).
      Use when adding one module or re-syncing a small subset without overwriting
      unrelated files under src/main/perl/lib/.

  perl dev/import-perl5/sync.pl --help
      Show this message.

  perl dev/import-perl5/sync.pl --verify-idempotent
      Replay the selected manifest twice and fail if the second sync changes
      any imported or generated output.

Protected targets (protected: true in YAML) are skipped on existing single-file
imports. For directory imports, protected paths are excluded from rsync; that list
is always computed from the full config even when --only is used.

USAGE
}

# Parse CLI; dies on unknown args. Returns the optional --only needle and the
# second-pass verification flag.
sub parse_argv {
    my $only_needle;
    my $verify_idempotent = 0;
    my $i = 0;
    while ($i < @ARGV) {
        my $a = $ARGV[$i++];
        if ($a eq '--help' || $a eq '-h') {
            usage();
            exit 0;
        }
        if ($a eq '--only') {
            die "sync.pl: --only requires a substring argument\n" if $i >= @ARGV;
            $only_needle = $ARGV[$i++];
            die "sync.pl: --only substring must be non-empty\n" if !defined $only_needle || $only_needle eq '';
        }
        elsif ($a =~ /^--only=(.+)\z/) {
            $only_needle = $1;
            die "sync.pl: --only substring must be non-empty\n" if $only_needle eq '';
        }
        elsif ($a eq '--verify-idempotent') {
            $verify_idempotent = 1;
        }
        elsif ($a =~ /^-/) {
            die "sync.pl: unknown option '$a' (try --help)\n";
        }
        else {
            die "sync.pl: unexpected argument '$a' (try --help)\n";
        }
    }
    return ($only_needle, $verify_idempotent);
}

sub file_sha256 {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $digest = Digest::SHA->new(256);
    $digest->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $digest->hexdigest;
}

sub record_snapshot_path {
    my ($state, $project_root, $path) = @_;
    my $relative = File::Spec->abs2rel($path, $project_root);
    if (-l $path) {
        my $destination = readlink $path;
        die "Cannot read imported symlink $path: $!\n" unless defined $destination;
        $state->{$relative} = "symlink\0$destination";
    }
    elsif (-f $path) {
        my $mode = (stat($path))[2] & 07777;
        $state->{$relative} = join "\0", 'file', sprintf('%04o', $mode),
            file_sha256($path);
    }
    elsif (-d $path) {
        my $mode = (stat($path))[2] & 07777;
        $state->{"$relative/"} = join "\0", 'directory', sprintf('%04o', $mode);
    }
    else {
        $state->{$relative} = 'missing';
    }
}

sub snapshot_import_targets {
    my ($imports, $project_root) = @_;
    my %roots;
    for my $import (@$imports) {
        my $target = File::Spec->catfile($project_root, $import->{target});
        $roots{$target} = 1;
        if (($import->{type} // '') eq 'generated_unicode_testprop') {
            my ($volume, $directories, $base) = File::Spec->splitpath($target);
            $base =~ s/\.pl\z//;
            for my $chunk (1 .. 10) {
                my $chunk_name = sprintf '%s-%02d.pl', $base, $chunk;
                $roots{File::Spec->catpath(
                    $volume, $directories, $chunk_name)} = 1;
            }
        }
    }
    my %state;
    for my $root (sort keys %roots) {
        if (-d $root && !-l $root) {
            my @paths;
            find({ no_chdir => 1, wanted => sub {
                push @paths, $File::Find::name } }, $root);
            record_snapshot_path(\%state, $project_root, $_) for sort @paths;
        }
        else {
            record_snapshot_path(\%state, $project_root, $root);
        }
    }
    return \%state;
}

sub snapshot_differences {
    my ($before, $after) = @_;
    my %paths = map { $_ => 1 } (keys %$before, keys %$after);
    return sort grep {
        !exists($before->{$_}) || !exists($after->{$_})
            || $before->{$_} ne $after->{$_}
    } keys %paths;
}

# Main script
sub main {
    # Determine project root (3 levels up from this script)
    my $script_dir = dirname(abs_path($0));
    my $project_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
    my $patches_dir = File::Spec->catdir($script_dir, 'patches');
    my $config_file = File::Spec->catdir($script_dir, 'config.yaml');

    my ($only_needle, $verify_idempotent) = parse_argv();

    unless (-f $config_file) {
        die "Configuration file not found: $config_file\n";
    }

    my $imports_all = parse_yaml($config_file);

    unless (@$imports_all) {
        print "No imports found in configuration.\n";
        return;
    }

    my @protected_files;
    for my $import (@$imports_all) {
        if ($import->{protected} && $import->{target}) {
            push @protected_files, $import->{target};
        }
    }

    my $imports = $imports_all;
    if (defined $only_needle) {
        my @filtered = grep {
            my $s = $_->{source} // '';
            my $t = $_->{target} // '';
            index($s, $only_needle) >= 0 || index($t, $only_needle) >= 0
        } @$imports_all;
        unless (@filtered) {
            die "sync.pl: no imports matched --only '$only_needle' "
                . "(try a substring of source: or target: in config.yaml)\n";
        }
        $imports = \@filtered;
    }

    print "PerlOnJava Perl5 Import Tool\n";
    print "=" x 60 . "\n";
    print "Project root: $project_root\n";
    print "Config file: $config_file\n\n";

    if (@protected_files) {
        print "Protected paths from config (" . scalar(@protected_files) . "):\n";
        print "  $_\n" for @protected_files;
        print "\n";
    }

    if (defined $only_needle) {
        print "Filtered mode: " . scalar(@$imports) . " import(s) matching --only '$only_needle'\n";
        print "(of " . scalar(@$imports_all) . " total in config.yaml)\n\n";
    } else {
        print "Full manifest: " . scalar(@$imports_all) . " import(s) to process.\n\n";
    }
    
    my $success_count = 0;
    my $error_count = 0;
    
    # Process each import
    for my $import (@$imports) {
        my $source = File::Spec->catfile($project_root, $import->{source});
        my $target = File::Spec->catfile($project_root, $import->{target});
        my $type = $import->{type} || 'file';
        
        print "Processing: $import->{source}\n";
        
        # Check if source exists
        if ($type eq 'generated_unicode_testprop') {
            unless (generate_unicode_testprop($import->{source}, $target, $project_root)) {
                $error_count++;
                next;
            }
        }
        elsif ($type eq 'generated_unicode_name_source') {
            unless (generate_unicode_name_source($import->{source}, $target,
                                                 $project_root)) {
                $error_count++;
                next;
            }
        }
        elsif ($type eq 'directory') {
            unless (-d $source) {
                warn "  ERROR: Source directory not found: $source\n\n";
                $error_count++;
                next;
            }
            
            # Create target directory if needed
            unless (-d $target) {
                print "  Creating directory: $target\n";
                make_path($target) or do {
                    warn "  ERROR: Cannot create directory: $!\n\n";
                    $error_count++;
                    next;
                };
            }
            
            # Copy directory using rsync (with protected file exclusions and explicit excludes)
            unless (copy_directory($source, $target, $project_root, \@protected_files, $import->{exclude})) {
                $error_count++;
                next;
            }
        }
        else {
            # Handle file import
            unless (-f $source) {
                warn "  ERROR: Source file not found: $source\n\n";
                $error_count++;
                next;
            }
            
            # Check if target is protected (defined in config.yaml)
            if ($import->{protected} && -f $target) {
                print "  ⚠ SKIPPED: File is protected from overwrite\n";
                print "  (File exists and protected flag is set in config)\n\n";
                $success_count++;
                next;
            }
            
            # Create target directory if needed
            my $target_dir = dirname($target);
            unless (-d $target_dir) {
                print "  Creating directory: $target_dir\n";
                make_path($target_dir) or do {
                    warn "  ERROR: Cannot create directory: $!\n\n";
                    $error_count++;
                    next;
                };
            }
            
            my $patch_applied = 0;
            # Stage file and its patch before replacing the target.
            print "  Copying to: $import->{target}\n";
            my ($temporary, $temporary_path) = tempfile('.import-XXXXXX', DIR => $target_dir, UNLINK => 0);
            close $temporary;
            unless (copy($source, $temporary_path)) {
                warn "  ERROR: Copy failed: $!\n\n";
                $error_count++;
                next;
            }
            if ($import->{patch}) {
                my $patch_file = File::Spec->catfile($patches_dir, $import->{patch});
                unless (-f $patch_file && apply_patch($temporary_path, $patch_file)) {
                    unlink $temporary_path;
                    $error_count++;
                    next;
                }
                $patch_applied = 1;
            }
            rename $temporary_path, $target or do {
                warn "  ERROR: Cannot publish staged import: $!\n\n";
                unlink $temporary_path;
                $error_count++;
                next;
            };
            $import->{_patch_applied} = $patch_applied;
        }
        
        # Apply patch if specified
        if ($import->{patch} && !$import->{_patch_applied}) {
            my $patch_file = File::Spec->catfile($patches_dir, $import->{patch});
            unless (-f $patch_file) {
                warn "  ERROR: Patch file not found: $patch_file\n\n";
                $error_count++;
                next;
            }
            
            unless (apply_patch($target, $patch_file)) {
                $error_count++;
                next;
            }
        }
        
        print "  ✓ Success\n\n";
        $success_count++;
    }
    
    # Create perl5_t/lib for opendir tests. It remains empty except for
    # explicitly configured generated test fixtures such as unicore/TestProp.pl.
    my $lib_dir = File::Spec->catdir($project_root, 'perl5_t', 'lib');
    unless (-d $lib_dir) {
        print "Creating empty perl5_t/lib directory...\n";
        make_path($lib_dir) or warn "Could not create $lib_dir: $!\n";
        print "  ✓ Created empty lib directory\n\n";
    }
    
    # Summary
    print "=" x 60 . "\n";
    print "Summary:\n";
    print "  Successful: $success_count\n";
    print "  Errors: $error_count\n";
    print "\n";
    
    if ($error_count > 0) {
        exit 1;
    }

    if ($verify_idempotent) {
        my $first = snapshot_import_targets($imports, $project_root);
        my @second_sync = ($^X, abs_path($0));
        push @second_sync, '--only', $only_needle if defined $only_needle;
        print "Running second sync for idempotence verification...\n";
        my $result = system @second_sync;
        if ($result != 0) {
            my $status = $result == -1 ? "could not start: $!"
                : ($result & 127) ? 'signal ' . ($result & 127)
                : 'exit ' . ($result >> 8);
            die "sync.pl: second sync failed ($status)\n";
        }
        my $second = snapshot_import_targets($imports, $project_root);
        my @differences = snapshot_differences($first, $second);
        if (@differences) {
            my @reported = @differences > 20
                ? @differences[0 .. 19] : @differences;
            my $more = @differences > @reported
                ? "\n  ... and " . (@differences - @reported) . " more" : '';
            die "sync.pl: second sync changed imported outputs:\n  "
                . join("\n  ", @reported) . $more . "\n";
        }
        print "Idempotence verified: second sync changed no imported outputs.\n";
    }
}

main() unless caller;

1;
