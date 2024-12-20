#!/usr/bin/perl
use strict;
use warnings;
use HTTP::Tiny;
use JSON;
use Data::Dumper;
use Getopt::Long;
use URI::Escape;

# Define supported actions and options
my $search = 0;
my $direct = 0;
my $verbose = 0;

GetOptions(
    "search" => \$search,    # Search by driver class name
    "direct" => \$direct,    # Use direct Maven coordinates
    "verbose" => \$verbose,  # Enable verbose output
) or die "Error in command line arguments\n";

# Show usage if no action specified
die "Usage: $0 --search org.h2.Driver | --direct group:artifact:version\n"
    unless $search xor $direct;

my $input = shift @ARGV || die "Missing input argument\n";

# Common JDBC driver mappings
my %driver_mappings = (
    'org.h2.Driver' => 'com.h2database:h2',
    'com.mysql.jdbc.Driver' => 'mysql:mysql-connector-java',
    'org.postgresql.Driver' => 'org.postgresql:postgresql'
);

my $maven_coords;
if ($direct) {
    die "Invalid Maven coordinates format. Expected: group:artifact:version\n"
        unless $input =~ /^[^:]+:[^:]+:[^:]+$/;
    $maven_coords = $input;
    print "Using direct Maven coordinates: $maven_coords\n";
} else {
    if (exists $driver_mappings{$input}) {
        $input = $driver_mappings{$input};
        print "Mapped driver $input to known artifact\n" if $verbose;
    }

    my ($group, $artifact) = split ':', $input;
    $group ||= $input;  # Use full input as group if no colon found

    my $search_url = "https://search.maven.org/solrsearch/select?q=g:$group";
    $search_url .= "+AND+a:$artifact" if $artifact;
    $search_url .= "&rows=5&wt=json";

    print "Search URL: $search_url\n" if $verbose;

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

    my @docs = @{$data->{response}{docs}};
    if (@docs > 1) {
        print "Multiple matches found:\n";
        for my $i (0..$#docs) {
            print "[$i] $docs[$i]{id}\n";
        }
        print "Select number [0-" . $#docs . "]: ";
        my $choice = <STDIN>;
        chomp $choice;
        $maven_coords = $docs[$choice]{id};
    } else {
        $maven_coords = $docs[0]{id};
    }
    print "Adding JDBC driver: $input -> $maven_coords\n";
}

# Add to build.gradle
if (-f 'build.gradle') {
    my $gradle = read_file('build.gradle');
    unless ($gradle =~ /implementation ['"]$maven_coords['"]/) {
        $gradle =~ s/(dependencies \{)/$1\n    implementation "$maven_coords"/;
        write_file('build.gradle', $gradle);
        print "Updated build.gradle\n";
    }
}

# Add to pom.xml
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

print "Build configuration complete\n";

sub read_file {
    my $file = shift;
    local $/;
    open my $fh, '<', $file or die "Cannot open $file: $!";
    return <$fh>;
}

sub write_file {
    my ($file, $content) = @_;
    open my $fh, '>', $file or die "Cannot write $file: $!";
    print $fh $content;
}
