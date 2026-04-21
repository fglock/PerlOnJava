#!/usr/bin/perl
use strict;
use warnings;
use HTTP::Tiny;
use JSON;
use Data::Dumper;
use Getopt::Long;
use URI::Escape;

# PerlOnJava Configuration Script
#
# This script manages configuration settings and dependencies for the PerlOnJava project.
# It updates Configuration.java (the single source of truth for version info) and
# propagates version changes to all relevant files in the repository.
#
# USAGE:
#
#   ./Configure.pl                      # Show current configuration
#   ./Configure.pl -D version=5.43.0    # Update version everywhere
#   ./Configure.pl --upgrade            # Upgrade dependencies to latest versions
#
# VERSION UPDATE BEHAVIOR:
#
# The 'version' field is special - when updated, it also:
#   - Updates build.gradle and pom.xml version fields
#   - Updates jperl and jperl.bat launcher scripts
#   - Updates README.md feature support line
#   - Replaces all occurrences of perlonjava-X.Y.Z.jar in documentation
#   - Updates specific version references in Config.pm (paths, compatibility checks)
#
# Safety measures to protect historical version references:
#   - Only versions with minor >= 40 can be updated (protects 5.10, 5.38, etc.)
#   - Excluded directories: src/main/perl/, dev/
#   - Excluded files: docs/about/changelog.md
#   - Config.pm uses targeted patterns (not blanket replacement)
#
# Note: gitCommitId and gitCommitDate are managed by the build system (Gradle/Maven)
# and should NOT be set via this script.
#
# DEPENDENCY MANAGEMENT:
#
#   ./Configure.pl --search org.h2.Driver              # Search for JDBC driver
#   ./Configure.pl --direct com.h2database:h2:2.2.224  # Add direct Maven coordinates

# Define supported actions and options
my $search = 0;   # Flag to indicate if a search for JDBC driver by class name is requested
my $direct = 0;   # Flag to indicate if direct Maven coordinates are provided
my $verbose = 0;  # Flag to enable verbose output for debugging
my $upgrade = 0;  # Flag to upgrade dependencies to their latest versions
my %config;       # Hash to store key-value pairs for configuration updates
my $help = 0;     # Flag to show help message

my $java_config_filename = 'src/main/java/org/perlonjava/core/Configuration.java';

# Parse command-line options
Getopt::Long::Configure("no_ignore_case");
GetOptions(
    "h|help" => \$help,
    "search" => \$search,    # Search by driver class name
    "direct" => \$direct,    # Use direct Maven coordinates
    "verbose" => \$verbose,  # Enable verbose output
    "upgrade" => \$upgrade,  # Upgrade dependencies to latest versions
    "D=s%" => \%config,      # Key-value configuration pairs
) or show_help();

# Show help message if requested
show_help() if $help;

# Show current configuration if no specific actions are requested
show_config() unless ($search || $direct || $upgrade || %config || @ARGV);

# Update configuration if any key-value pairs are provided
update_configuration(\%config) if %config;

# Handle dependency management if search or direct options are provided
handle_dependencies() if ($search || $direct);

# Upgrade dependencies to their latest versions if the upgrade option is provided
update_to_latest_versions() if $upgrade;

# Function to display help message and usage instructions
sub show_help {
    print
        'Usage: ./Configure.pl [options]

Configuration Options:
    -h, --help                      Show this help message
    -D key=value                    Set configuration value

    Supported configuration keys:
      version     - PerlOnJava version (e.g., 5.43.0)
                    Updates Configuration.java, build files, and all JAR references

    Read-only keys (managed by build system):
      gitCommitId   - Git commit hash (set during build)
      gitCommitDate - Git commit date (set during build)

Dependency Management Options:
    --search                        Search for JDBC driver by class name
    --direct                        Use direct Maven coordinates
    --verbose                       Enable verbose output
    --upgrade                       Upgrade dependencies to their latest versions

Examples:
    ./Configure.pl                              # Show current configuration
    ./Configure.pl -D version=5.43.0            # Update version everywhere
    ./Configure.pl --search org.h2.Driver       # Search for JDBC driver
    ./Configure.pl --direct com.h2database:h2:2.2.224
    ./Configure.pl --upgrade                    # Upgrade all dependencies
';
    exit;
}

# Function to display the current configuration by reading a Java configuration file
sub show_config {
    my $java_config = read_file($java_config_filename);
    print "Current configuration:\n\n";
    # Use robust pattern that handles quoted strings and simple values
    while ($java_config =~ /public static final (\w+)\s+(\w+)\s*=\s*("[^"]*"|[^;]+);/g) {
        print "$2 = $3\n";
    }
    exit;
}

# Function to update configuration values in the Java configuration file
sub update_configuration {
    my $config = shift;
    my $file = $java_config_filename;
    my $content = read_file($file);

    foreach my $key (keys %$config) {
        my $value = $config->{$key};

        # Prevent setting git fields - they are managed by the build system
        if ($key eq 'gitCommitId' || $key eq 'gitCommitDate') {
            warn "Warning: $key is managed by the build system and cannot be set manually.\n";
            warn "These values are injected during Gradle/Maven builds.\n";
            next;
        }

        # Handle string values vs boolean/numeric values
        my $quoted_value = $value;
        $quoted_value = "\"$value\"" if $value !~ /^(?:true|false|\d+)$/;

        # Use robust pattern that matches quoted strings or simple values
        # Pattern: "..." for strings, or non-semicolon chars for booleans/numbers
        if ($content =~ /public static final \w+\s+\Q$key\E\s*=\s*("[^"]*"|[^;]+);/) {
            # Extract old value for special handling
            my $old_value;
            if ($content =~ /public static final \w+\s+\Q$key\E\s*=\s*"([^"]*)";/) {
                $old_value = $1;
            }

            # Special handling for 'version' field - validate BEFORE making changes
            if ($key eq 'version' && $old_value) {
                validate_version_change($old_value, $value);
            }

            # Perform the replacement
            $content =~ s/(public static final \w+\s+\Q$key\E\s*=\s*)("[^"]*"|[^;]+);/${1}$quoted_value;/;
            print "Updated $key = $quoted_value\n";

            # Special handling for 'version' field - propagate to all relevant files
            if ($key eq 'version' && $old_value) {
                update_version_everywhere($old_value, $value);
            }
        } else {
            warn "Warning: Key '$key' not found in $file\n";
        }
    }

    write_file($file, $content);
    print "\nConfiguration updated successfully\n";
}

# Function to validate version changes before applying them
sub validate_version_change {
    my ($old_version, $new_version) = @_;

    # Safety check: only allow updating versions where minor >= 40
    # This prevents accidentally replacing historical Perl version references
    # like 5.10, 5.38, etc. in documentation
    my ($old_major, $old_minor) = $old_version =~ /^(\d+)\.(\d+)/;
    my ($new_major, $new_minor) = $new_version =~ /^(\d+)\.(\d+)/;
    
    if (!defined $old_minor || $old_minor < 40) {
        die "Error: Cannot update from version $old_version (minor version must be >= 40)\n" .
            "This prevents accidentally replacing historical Perl version references.\n";
    }
    if (!defined $new_minor || $new_minor < 40) {
        die "Error: Cannot update to version $new_version (minor version must be >= 40)\n" .
            "This prevents accidentally replacing historical Perl version references.\n";
    }
}

# Function to update version references throughout the repository
sub update_version_everywhere {
    my ($old_version, $new_version) = @_;

    # Note: Version validation is done in validate_version_change() before this is called

    print "\nPropagating version change from $old_version to $new_version...\n\n";

    # Directories to exclude from raw version updates (but NOT from JAR reference updates)
    # These contain historical version references that should not be changed
    my @excluded_dirs = (
        './src/main/perl',      # Perl modules with historical version docs
        './dev',                # Design documents and development notes
    );

    # Files to exclude from version updates (relative to project root)
    # These contain historical changelogs and version history
    my @excluded_files = (
        './docs/about/changelog.md',
    );

    # First pass: Update JAR filename references in ALL files (always safe)
    # This includes: jperl, jperl.bat, docs, examples, src/main/perl comments, etc.
    # Matches both perlonjava-X.Y.Z.jar and perlonjava-X.Y.Z-all.jar
    my @all_files = `find . -type f -not -path "*/\\.*" -not -path "*/build/*" -not -path "*/target/*"`;
    foreach my $file (@all_files) {
        chomp $file;
        next if -B $file;  # Skip binary files

        my $file_content = read_file($file);
        # Match perlonjava-VERSION.jar or perlonjava-VERSION-all.jar
        if ($file_content =~ s/perlonjava-\Q$old_version\E(-all)?\.jar/"perlonjava-$new_version" . ($1 || "") . ".jar"/ge) {
            write_file($file, $file_content);
            print "Updated JAR reference in $file\n";
        }
    }

    # Second pass: Update version patterns in non-excluded files
    # Build exclusion patterns for find command
    my $exclude_pattern = join(' ', 
        map { qq{-not -path "$_/*"} } @excluded_dirs
    );

    my @files = `find . -type f -not -path "*/\\.*" -not -path "*/build/*" -not -path "*/target/*" $exclude_pattern`;
    foreach my $file (@files) {
        chomp $file;
        next if -B $file;  # Skip binary files
        next if grep { $file eq $_ } @excluded_files;  # Skip excluded files

        my $file_content = read_file($file);
        my $updated = 0;

        # Update version in build.gradle (project version only)
        if ($file =~ /build\.gradle$/ && $file_content =~ s/^(version\s*=\s*')$old_version(')\s*$/$1$new_version$2/m) {
            $updated = 1;
            print "Updated version in $file\n";
        }

        # Update version in pom.xml (project version, not dependency versions)
        if ($file =~ /pom\.xml$/) {
            # Match only the project version (near the top, after artifactId)
            if ($file_content =~ s|(<artifactId>perlonjava</artifactId>\s*<version>)$old_version(</version>)|$1$new_version$2|s) {
                $updated = 1;
                print "Updated version in $file\n";
            }
        }

        # Update version in README.md (feature support line)
        if ($file =~ /README\.md$/) {
            if ($file_content =~ s/(Supports most Perl )$old_version( features)/$1$new_version$2/g) {
                $updated = 1;
                print "Updated version in $file\n";
            }
        }

        write_file($file, $file_content) if $updated;
    }

    # Config.pm is handled separately since it's in an excluded directory
    # but needs specific version updates for runtime compatibility
    update_config_pm($old_version, $new_version);
}

# Function to update Config.pm with new version
# This is separate because Config.pm is in src/main/perl (excluded dir)
# but needs version updates for Perl runtime compatibility checks
sub update_config_pm {
    my ($old_version, $new_version) = @_;
    
    my $config_pm = './src/main/perl/lib/Config.pm';
    return unless -f $config_pm;
    
    my $content = read_file($config_pm);
    my $updated = 0;
    
    # Update version strings that are clearly PerlOnJava version identifiers
    # These patterns match Config.pm's version declarations, not historical references
    
    # Pattern: perlonjava => 'X.Y.Z'
    if ($content =~ s/(perlonjava\s*=>\s*')$old_version(')/$1$new_version$2/g) {
        $updated = 1;
    }
    
    # Pattern: version => 'X.Y.Z'  
    if ($content =~ s/(^\s*version\s*=>\s*')$old_version(')/$1$new_version$2/gm) {
        $updated = 1;
    }
    
    # Pattern: api_versionstring => 'X.Y.Z'
    if ($content =~ s/(api_versionstring\s*=>\s*')$old_version(')/$1$new_version$2/g) {
        $updated = 1;
    }
    
    # Pattern: lib version check - die "...(X.Y.Z)..."
    if ($content =~ s/(Perl lib version \()$old_version(\))/$1$new_version$2/g) {
        $updated = 1;
    }
    
    # Pattern: $^V eq X.Y.Z
    if ($content =~ s/(\$\^V eq )$old_version(\s)/$1$new_version$2/g) {
        $updated = 1;
    }
    
    # Pattern: path components like /5.42.0/
    if ($content =~ s|(/perl5/)$old_version(/)|$1$new_version$2|g) {
        $updated = 1;
    }
    if ($content =~ s|(/site_perl/)$old_version(/)|$1$new_version$2|g) {
        $updated = 1;
    }
    if ($content =~ s|(/vendor_perl/)$old_version(/)|$1$new_version$2|g) {
        $updated = 1;
    }
    
    if ($updated) {
        write_file($config_pm, $content);
        print "Updated version in $config_pm\n";
    }
}
# Function to handle dependency management based on search or direct options
sub handle_dependencies {
    # Show usage if no action specified
    die "Usage: $0 --search org.h2.Driver | --direct group:artifact:version\n"
        unless $search xor $direct;

    my $input = shift @ARGV || die "Missing input argument\n";

    my $maven_coords;
    if ($direct) {
        # Validate and use direct Maven coordinates
        die "Invalid Maven coordinates format. Expected: group:artifact:version\n"
            unless $input =~ /^[^:]+:[^:]+:[^:]+$/;
        $maven_coords = $input;
        print "Using direct Maven coordinates: $maven_coords\n";
    } else {
        # Search for Maven artifact based on input
        $maven_coords = search_maven_artifact($input);
    }

    # Update build files with the resolved Maven coordinates
    update_build_files($maven_coords);
}

# Function to search for a Maven artifact using the Maven Central Repository
sub search_maven_artifact {
    my $input = shift;
    my $rows = 50;

    # Construct search URL based on input type
    my $search_url;
    if ($input =~ /\.Driver$/) {
        # Class name search
        $search_url = "https://search.maven.org/solrsearch/select?q=fc:$input&rows=$rows&wt=json";
    } elsif ($input =~ /:/) {
        # Group:artifact search
        my ($group, $artifact) = split ':', $input;
        $search_url = "https://search.maven.org/solrsearch/select?q=g:$group+AND+a:$artifact&rows=$rows&wt=json";
    } else {
        # Keyword search - using exact text match
        my $encoded_input = uri_escape($input);
        $search_url = "https://search.maven.org/solrsearch/select?q=a:$encoded_input+OR+text:$encoded_input&rows=$rows&wt=json";
    }

    print "Search URL: $search_url\n" if $verbose;

    # Perform HTTP request with retries
    my $http = HTTP::Tiny->new;
    my $max_retries = 3;
    my $retry_count = 0;
    my $response;

    while ($retry_count < $max_retries) {
        $response = $http->get($search_url);
        last if $response->{success};
        $retry_count++;
        sleep 1;
    }

    die "Failed to fetch data: $response->{status} $response->{reason}\n"
        unless $response->{success};

    my $data = decode_json($response->{content});
    print Dumper($data) if $verbose;

    die "No Maven artifacts found for: $input\n"
        unless $data->{response}{docs} && @{$data->{response}{docs}};

    # Sort results by relevance score
    my @docs = sort {
        score_jdbc_relevance($b) <=> score_jdbc_relevance($a)
    } @{$data->{response}{docs}};

    # Take top 10 most relevant results
    @docs = @docs[0..9] if @docs > 10;

    if (@docs > 1) {
        print "Multiple matches found:\n";
        for my $i (0..$#docs) {
            print "[$i] $docs[$i]{id}\n";
        }
        print "Select number [0-" . $#docs . "]: ";
        my $choice = <STDIN>;
        unless (defined $choice) {
            warn "No input received (not running interactively?). Defaulting to [0].\n";
            $choice = 0;
        }
        chomp $choice;
        $choice = 0 unless $choice =~ /^\d+$/ && $choice <= $#docs;
        return $docs[$choice]{id};
    }

    return $docs[0]{id};
}

# Function to update build files (build.gradle or pom.xml) with the specified Maven coordinates
sub update_build_files {
    my $maven_coords = shift;

    # Add to build.gradle if present
    if (-f 'build.gradle') {
        my $gradle = read_file('build.gradle');
        unless ($gradle =~ /implementation ['"]${maven_coords}['"]/) {
            $gradle =~ s/(dependencies \{)/$1\n    implementation "$maven_coords"/;
            write_file('build.gradle', $gradle);
            print "Updated build.gradle\n";
        }
    }

    # Add to pom.xml if present
    if (-f 'pom.xml') {
        my ($group, $artifact, $version) = split ':', $maven_coords;
        if (!defined $version) {
            warn "No version specified for $group:$artifact. Using 'LATEST' as the version.";
            $version = 'LATEST';
        }
        my $pom = read_file('pom.xml');
        unless ($pom =~ /<artifactId>$artifact<\/artifactId>/) {
            my $dep = "\n        <dependency>\n" .
                "            <groupId>$group</groupId>\n" .
                "            <artifactId>$artifact</artifactId>\n" .
                "            <version>$version</version>\n" .
                "        </dependency>";
            $pom =~ s/(<dependencies>)/$1$dep/;
            write_file('pom.xml', $pom);
            print "Updated pom.xml\n";
        }
    }
}

# Function to update project dependencies to their latest versions
sub update_to_latest_versions {
    print "Upgrading project dependencies to latest versions...\n";
    # Update Maven dependencies
    if (-f 'pom.xml') {
        print "Updating Maven dependencies to latest versions...\n";
        my $maven_output = `mvn versions:use-latest-versions`;
        my $maven_status = $? >> 8;
        if ($maven_status != 0) {
            warn "Failed to update Maven dependencies. Exit status: $maven_status\n";
            warn "Output: $maven_output\n";
        } else {
            print "Maven dependencies updated successfully.\n";
            print "Output: $maven_output\n";
        }
    }

    # Update Gradle dependencies using version catalog
    if (-f 'build.gradle') {
        print "Updating Gradle dependencies to latest versions using version catalog...\n";
        my $gradle_output = `./gradlew versionCatalogUpdate`;
        my $gradle_status = $? >> 8;
        if ($gradle_status != 0) {
            warn "Failed to update Gradle dependencies. Exit status: $gradle_status\n";
            warn "Output: $gradle_output\n";
        } else {
            print "Gradle dependencies updated successfully.\n";
            print "Output: $gradle_output\n";
        }
    }
}

# Function to read the content of a file
sub read_file {
    my $file = shift;
    local $/;
    open my $fh, '<', $file or die "Cannot open $file: $!";
    return <$fh>;
}

# Function to write content to a file
sub write_file {
    my ($file, $content) = @_;
    open my $fh, '>', $file or die "Cannot write $file: $!";
    print $fh $content;
}

# Function to score artifacts based on JDBC relevance indicators
sub score_jdbc_relevance {
    my $doc = shift;
    my $score = 0;

    # Higher score for JDBC indicators
    my $g = $doc->{g} // '';
    my $a = $doc->{a} // '';
    my $v = $doc->{latestVersion} // '';
    my $c = $doc->{c} // '';
    $score += 5 if $g =~ /jdbc/i;
    $score += 5 if $a =~ /jdbc/i;
    $score += 3 if $g =~ /database|mysql|postgresql|oracle|sqlserver/i;
    $score += 3 if $a =~ /database|mysql|postgresql|oracle|sqlserver/i;
    $score += 2 if $v =~ /jdbc/i;
    $score += 4 if $c =~ /Driver$/;

    # Boost score based on download count
    $score += log($doc->{downloadCount} || 1);

    return $score;
}
