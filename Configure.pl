#!/usr/bin/perl
use strict;
use warnings;
use HTTP::Tiny;
use JSON;
use Data::Dumper;
use Getopt::Long;
use URI::Escape;

# This script is designed to manage configuration settings and dependencies
# for a Java project. It provides options to update configuration values
# in a Java configuration file and manage dependencies using Maven coordinates.
# The script can search for JDBC drivers by class name or use direct Maven
# coordinates to update build files like build.gradle or pom.xml.

# Note: This script can be run using the following commands:
#
# ./Configure.pl -D perlVersion=v5.40.0
# ./Configure.pl -D jarVersion=3.0.1
#

# Define supported actions and options for both dependency management and configuration
my $search = 0;   # Flag to indicate if a search for JDBC driver by class name is requested
my $direct = 0;   # Flag to indicate if direct Maven coordinates are provided
my $verbose = 0;  # Flag to enable verbose output for debugging
my $upgrade = 0;  # Flag to upgrade dependencies to their latest versions
my %config;       # Hash to store key-value pairs for configuration updates
my $help = 0;     # Flag to show help message

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

Dependency Management Options:
    --search                        Search for JDBC driver by class name
    --direct                        Use direct Maven coordinates
    --verbose                       Enable verbose output
    --upgrade                       Upgrade dependencies to their latest versions

Examples:
    ./Configure.pl -D strict_mode=true -D enable_optimizations=false
    ./Configure.pl --search org.h2.Driver
    ./Configure.pl --direct com.h2database:h2:2.2.224
    ./Configure.pl --upgrade
';
    exit;
}

# Function to display the current configuration by reading a Java configuration file
sub show_config {
    my $java_config = read_file('src/main/java/org/perlonjava/Configuration.java');
    print "Current configuration:\n\n";
    while ($java_config =~ /public static final (\w+)\s+(\w+)\s*=\s*(.+?);/g) {
        print "$2 = $3\n";
    }
    exit;
}

# Function to update configuration values in the Java configuration file
sub update_configuration {
    my $config = shift;
    my $file = 'src/main/java/org/perlonjava/Configuration.java';
    my $content = read_file($file);

    foreach my $key (keys %$config) {
        my $value = $config->{$key};
        # Handle string values vs boolean/numeric values
        $value = "\"$value\"" if $value !~ /^(?:true|false|\d+)$/;

        if ($content =~ /public static final \w+\s+\Q$key\E\s*=\s*.+?;/) {
            my $old_value = $1 if $content =~ /public static final \w+\s+\Q$key\E\s*=\s*"(.+?)";/;
            $content =~ s/(public static final \w+\s+\Q$key\E\s*=\s*).+?;/$1$value;/;
            print "Updated $key = $value\n";

            # Special handling for jarVersion updates
            if ($key eq 'jarVersion' && $old_value) {
                my $old_jar = "perlonjava-$old_value.jar";
                my $new_jar = "perlonjava-" . $value =~ s/"//gr . ".jar";

                # Find and update all files in the repository
                my @files = `find . -type f -not -path "*/\.*"`;
                foreach my $file (@files) {
                    chomp $file;
                    next if -B $file;  # Skip binary files

                    my $file_content = read_file($file);
                    if ($file_content =~ s/\Q$old_jar\E/$new_jar/g) {
                        write_file($file, $file_content);
                        print "Updated jar version in $file\n";
                    }
                }
            }
        }
    }

    write_file($file, $content);
    print "\nConfiguration updated successfully\n";
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
        chomp $choice;
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
    $score += 5 if $doc->{g} =~ /jdbc/i;
    $score += 5 if $doc->{a} =~ /jdbc/i;
    $score += 3 if $doc->{g} =~ /database|mysql|postgresql|oracle|sqlserver/i;
    $score += 3 if $doc->{a} =~ /database|mysql|postgresql|oracle|sqlserver/i;
    $score += 2 if $doc->{latestVersion} =~ /jdbc/i;
    $score += 4 if $doc->{c} =~ /Driver$/;

    # Boost score based on download count
    $score += log($doc->{downloadCount} || 1);

    return $score;
}
