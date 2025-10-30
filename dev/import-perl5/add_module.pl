#!/usr/bin/env perl
use strict;
use warnings;
use File::Find;
use File::Basename;
use File::Spec;
use Cwd qw(abs_path);
use Digest::MD5 qw(md5_hex);

=head1 NAME

add_module.pl - Add a Perl module and its tests to sync configuration

=head1 SYNOPSIS

    perl dev/import-perl5/add_module.pl Module::Name
    perl dev/import-perl5/add_module.pl File/Basename.pm
    perl dev/import-perl5/add_module.pl --dry-run Module::Name
    perl dev/import-perl5/add_module.pl --help

=head1 DESCRIPTION

This script helps add Perl modules from perl5/ to the sync.pl configuration.
It will:
1. Find the module in src/main/perl/lib
2. Locate the original source in perl5/
3. Check if it's already configured
4. Find related test files
5. Generate config entries (or add them with --apply)

=cut

# Parse command line
my $dry_run = 1;  # Default to dry run for safety
my $module_name;
my $help;

for my $arg (@ARGV) {
    if ($arg eq '--apply') {
        $dry_run = 0;
    } elsif ($arg eq '--dry-run') {
        $dry_run = 1;
    } elsif ($arg eq '--help' || $arg eq '-h') {
        $help = 1;
    } elsif (!$module_name) {
        $module_name = $arg;
    }
}

if ($help || !$module_name) {
    print <<'HELP';
Usage: perl dev/import-perl5/add_module.pl [options] <module>

Options:
    --dry-run    Show what would be added (default)
    --apply      Actually add to config.yaml
    --help       Show this help

Examples:
    perl dev/import-perl5/add_module.pl Digest::MD5
    perl dev/import-perl5/add_module.pl File/Basename.pm
    perl dev/import-perl5/add_module.pl --apply Text::Wrap
HELP
    exit($help ? 0 : 1);
}

# Determine project root
my $script_dir = dirname(abs_path($0));
my $project_root = abs_path(File::Spec->catdir($script_dir, '..', '..'));
my $config_file = File::Spec->catfile($script_dir, 'config.yaml');
my $src_dir = File::Spec->catdir($project_root, 'src', 'main', 'perl', 'lib');
my $perl5_dir = File::Spec->catdir($project_root, 'perl5');

print "PerlOnJava Module Addition Tool\n";
print "=" x 60 . "\n";
print "Project root: $project_root\n";
print "Mode: " . ($dry_run ? "DRY RUN" : "APPLY CHANGES") . "\n";
print "\n";

# Convert module name to file path
my $rel_path = $module_name;
if ($rel_path =~ /::/) {
    $rel_path =~ s/::/\//g;
    $rel_path .= '.pm' unless $rel_path =~ /\.(pm|pl)$/;
}

my $src_file = File::Spec->catfile($src_dir, $rel_path);

unless (-f $src_file) {
    die "ERROR: Module not found: $src_file\n" .
        "Make sure the module exists in src/main/perl/lib/\n";
}

print "Found module: $src_file\n";

# Read existing config to check for duplicates
my %configured = read_config($config_file);

# Find the original in perl5/
my $filename = basename($rel_path);
my @possible = find_in_perl5($perl5_dir, $filename);

unless (@possible) {
    die "ERROR: Could not find $filename in perl5/ directory\n";
}

print "Found " . scalar(@possible) . " potential source(s) in perl5/\n";

# Compare each to find the best match
my $best_match;
my $best_similarity = 0;

my $src_content = read_file($src_file);

for my $perl5_file (@possible) {
    my $perl5_content = read_file($perl5_file);
    my $similarity = calculate_similarity($src_content, $perl5_content);
    
    if ($similarity > $best_similarity) {
        $best_similarity = $similarity;
        $best_match = $perl5_file;
    }
}

if ($best_similarity < 0.7) {
    die "ERROR: No good match found (best: " . sprintf("%.1f%%", $best_similarity * 100) . ")\n";
}

print "Best match: $best_match\n";
print "Similarity: " . sprintf("%.1f%%", $best_similarity * 100) . "\n\n";

# Make paths relative to project root
my $perl5_rel = File::Spec->abs2rel($best_match, $project_root);
my $target_rel = File::Spec->abs2rel($src_file, $project_root);

# Check if already configured
if (exists $configured{$perl5_rel}) {
    print "Module is already configured in config.yaml\n";
    exit(0);
}

# Generate config entries
my @entries;
my $comment = get_category_comment($perl5_rel);

push @entries, "  # $comment" if $comment;
push @entries, "  - source: $perl5_rel";
push @entries, "    target: $target_rel";
push @entries, "";

# Find test files
my @test_entries = find_tests($perl5_rel, $rel_path, \%configured);

if (@test_entries) {
    push @entries, @test_entries;
}

# Output
print "=" x 60 . "\n";
print "Generated configuration:\n";
print "=" x 60 . "\n";
for my $line (@entries) {
    print "$line\n";
}
print "\n";

if ($dry_run) {
    print "This was a DRY RUN. Use --apply to add these entries to config.yaml\n";
} else {
    add_to_config($config_file, \@entries);
    print "✓ Added to config.yaml\n";
}

exit(0);

#
# Subroutines
#

sub read_config {
    my ($file) = @_;
    my %configured;
    
    open my $fh, '<', $file or die "Cannot open $file: $!\n";
    while (my $line = <$fh>) {
        if ($line =~ /^\s*-?\s*source:\s+(.+)/) {
            my $source = $1;
            $source =~ s/^\s+|\s+$//g;
            $configured{$source} = 1;
        }
    }
    close $fh;
    
    return %configured;
}

sub find_in_perl5 {
    my ($dir, $filename) = @_;
    my @found;
    
    my $find_cmd = "find '$dir' -name '$filename' -type f 2>/dev/null";
    @found = `$find_cmd`;
    chomp @found;
    
    return @found;
}

sub read_file {
    my ($file) = @_;
    open my $fh, '<', $file or return '';
    local $/;
    return <$fh>;
}

sub calculate_similarity {
    my ($str1, $str2) = @_;
    
    # Normalize code
    $str1 = normalize_code($str1);
    $str2 = normalize_code($str2);
    
    return 1.0 if $str1 eq $str2;
    return 1.0 if md5_hex($str1) eq md5_hex($str2);
    
    # Line-based similarity
    my @lines1 = split /\n/, $str1;
    my @lines2 = split /\n/, $str2;
    
    my $total_lines = @lines1 > @lines2 ? @lines1 : @lines2;
    return 0 if $total_lines == 0;
    
    my %lines1 = map { $_ => 1 } @lines1;
    my $matching = 0;
    for my $line (@lines2) {
        $matching++ if exists $lines1{$line};
    }
    
    return $matching / $total_lines;
}

sub normalize_code {
    my ($code) = @_;
    $code =~ s/#.*$//mg;
    $code =~ s/^=\w+.*?^=cut\s*$//mgs;
    $code =~ s/\s+/ /g;
    return $code;
}

sub get_category_comment {
    my ($path) = @_;
    
    return "From CPAN distribution" if $path =~ m{perl5/cpan/};
    return "From core distribution" if $path =~ m{perl5/dist/};
    return "From extension" if $path =~ m{perl5/ext/};
    return "From core library" if $path =~ m{perl5/lib/};
    return "";
}

sub find_tests {
    my ($source_path, $rel_path, $configured) = @_;
    my @entries;
    
    # Determine test directory based on source location
    my $test_dir;
    if ($source_path =~ m{^(perl5/(?:cpan|dist|ext)/[^/]+)}) {
        $test_dir = "$1/t";
    } elsif ($source_path =~ m{^perl5/lib/}) {
        # Tests might be in same directory or perl5/t
        my $dir = dirname($source_path);
        my $base = basename($source_path, '.pm', '.pl');
        
        # Check for .t file with same name
        my $test_file = "$dir/$base.t";
        if (-f File::Spec->catfile($project_root, $test_file) && !exists $configured->{$test_file}) {
            my $target_path = dirname($rel_path);
            my $target_test = $target_path eq '.' ? "perl5_t/$base.t" : "perl5_t/$target_path/$base.t";
            
            push @entries, "  # Related test";
            push @entries, "  - source: $test_file";
            push @entries, "    target: $target_test";
            push @entries, "";
            return @entries;
        }
        return @entries;
    } else {
        return @entries;
    }
    
    my $test_dir_abs = File::Spec->catfile($project_root, $test_dir);
    return @entries unless -d $test_dir_abs;
    
    # Check if test directory is already configured
    return @entries if exists $configured->{$test_dir};
    
    # Suggest adding whole test directory
    my $dist_name = $1 if $source_path =~ m{perl5/(?:cpan|dist|ext)/([^/]+)};
    my $target_test = "perl5_t/$dist_name";
    
    push @entries, "  # Tests for distribution";
    push @entries, "  - source: $test_dir";
    push @entries, "    target: $target_test";
    push @entries, "    type: directory";
    push @entries, "";
    
    return @entries;
}

sub add_to_config {
    my ($config_file, $entries) = @_;
    
    # Read the config file
    open my $fh, '<', $config_file or die "Cannot open $config_file: $!\n";
    my @lines = <$fh>;
    close $fh;
    
    # Find the insertion point (before the "Add more imports" comment)
    my $insert_pos = -1;
    for my $i (0 .. $#lines) {
        if ($lines[$i] =~ /^\s*#\s*Add more imports below/) {
            $insert_pos = $i;
            last;
        }
    }
    
    if ($insert_pos == -1) {
        # Just append to end
        $insert_pos = scalar @lines;
    }
    
    # Insert the new entries
    splice @lines, $insert_pos, 0, map { "$_\n" } @$entries;
    
    # Write back
    open my $out, '>', $config_file or die "Cannot write $config_file: $!\n";
    print $out @lines;
    close $out;
}

