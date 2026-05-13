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
# Options: --help, --only SUBSTRING (matches source: or target: field)
use strict;
use warnings;
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Copy qw(copy);
use File::Spec;
use Cwd qw(abs_path);

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
    my $cmd = "patch --no-backup-if-mismatch -p0 '$target' < '$patch_file'";
    print "  Applying patch: $patch_file\n";
    
    my $result = system($cmd);
    if ($result != 0) {
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

Protected targets (protected: true in YAML) are skipped on existing single-file
imports. For directory imports, protected paths are excluded from rsync; that list
is always computed from the full config even when --only is used.

USAGE
}

# Parse CLI; dies on unknown args. Returns optional --only needle or undef.
sub parse_argv {
    my $only_needle;
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
        elsif ($a =~ /^-/) {
            die "sync.pl: unknown option '$a' (try --help)\n";
        }
        else {
            die "sync.pl: unexpected argument '$a' (try --help)\n";
        }
    }
    return $only_needle;
}

# Main script
sub main {
    # Determine project root (3 levels up from this script)
    my $script_dir = dirname(abs_path($0));
    my $project_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
    my $patches_dir = File::Spec->catdir($script_dir, 'patches');
    my $config_file = File::Spec->catdir($script_dir, 'config.yaml');

    my $only_needle = parse_argv();

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
        if ($type eq 'directory') {
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
            
            # Copy file
            print "  Copying to: $import->{target}\n";
            unless (copy($source, $target)) {
                warn "  ERROR: Copy failed: $!\n\n";
                $error_count++;
                next;
            }
        }
        
        # Apply patch if specified
        if ($import->{patch}) {
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
    
    # Create empty perl5_t/lib directory (needed for opendir tests but must stay empty)
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
}

main();
