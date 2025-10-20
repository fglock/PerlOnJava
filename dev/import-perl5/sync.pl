#!/usr/bin/env perl
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
        }
    }
    push @imports, $current_import if $current_import;
    close $fh;
    
    return \@imports;
}

# Apply a patch file to a target
sub apply_patch {
    my ($target, $patch_file) = @_;
    
    my $cmd = "patch -p0 '$target' < '$patch_file'";
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
    my ($source, $target) = @_;
    
    # Use rsync for efficient directory copying
    my $cmd = "rsync -a '$source/' '$target/'";
    print "  Running: $cmd\n";
    
    my $result = system($cmd);
    if ($result != 0) {
        warn "  Warning: rsync failed with exit code $result\n";
        return 0;
    }
    return 1;
}

# Main script
sub main {
    # Determine project root (3 levels up from this script)
    my $script_dir = dirname(abs_path($0));
    my $project_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
    my $patches_dir = File::Spec->catdir($script_dir, 'patches');
    my $config_file = File::Spec->catdir($script_dir, 'config.yaml');
    
    print "PerlOnJava Perl5 Import Tool\n";
    print "=" x 60 . "\n";
    print "Project root: $project_root\n";
    print "Config file: $config_file\n\n";
    
    # Check if config exists
    unless (-f $config_file) {
        die "Configuration file not found: $config_file\n";
    }
    
    # Parse configuration
    my $imports = parse_yaml($config_file);
    
    unless (@$imports) {
        print "No imports found in configuration.\n";
        return;
    }
    
    print "Found " . scalar(@$imports) . " import(s) to process.\n\n";
    
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
            
            # Copy directory using rsync
            unless (copy_directory($source, $target)) {
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
