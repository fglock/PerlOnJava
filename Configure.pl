#!/usr/bin/perl
use strict;
use warnings;
use HTTP::Tiny;
use JSON;
use Data::Dumper;

my $driver = shift @ARGV || die "Usage: $0 org.h2.Driver\n";

# Search for driver class
my $search_url = "https://search.maven.org/solrsearch/select?q=$driver&rows=1&wt=json";

my $http = HTTP::Tiny->new;
my $response = $http->get($search_url);
my $data = decode_json($response->{content});
my $maven_coords = $data->{response}{docs}[0]{id};

print "Adding JDBC driver: $driver -> $maven_coords\n";

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

