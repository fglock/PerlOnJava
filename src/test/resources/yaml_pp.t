use feature 'say';
use strict;
use YAML::PP;

# Basic OO interface tests
my $ypp = YAML::PP->new;
print "not " unless defined $ypp;
say "ok # YAML::PP constructor";

# Simple scalar mapping
my $yaml = "name: Alice\nage: 25\n";
my @docs = $ypp->load_string($yaml);
print "not " unless $docs[0]->{name} eq 'Alice' && $docs[0]->{age} == 25;
say "ok # Basic mapping load";

# Array/sequence test
$yaml = "- apple\n- banana\n- cherry\n";
@docs = $ypp->load_string($yaml);
print "not " unless $docs[0]->[0] eq 'apple' && $docs[0]->[2] eq 'cherry';
say "ok # Sequence load";

# Nested structures
$yaml = "outer:\n  inner:\n    value: 42\n";
@docs = $ypp->load_string($yaml);
print "not " unless $docs[0]->{outer}{inner}{value} == 42;
say "ok # Nested structure load";

# Multiple documents
$yaml = "---\nfirst: 1\n---\nsecond: 2\n";
@docs = $ypp->load_string($yaml);
print "not " unless @docs == 2 && $docs[0]->{first} == 1 && $docs[1]->{second} == 2;
say "ok # Multiple documents load";

# Dump simple hash
my $data = { name => 'Bob', numbers => [1, 2, 3] };
$yaml = $ypp->dump_string($data);
my @parsed = $ypp->load_string($yaml);
print "not " unless $parsed[0]->{name} eq 'Bob' && $parsed[0]->{numbers}[1] == 2;
say "ok # Dump and reload hash";

# Test file operations
my $tempfile = "test_yaml_$$.yml";
$ypp->dump_file($tempfile, $data);
@docs = $ypp->load_file($tempfile);
print "not " unless $docs[0]->{name} eq 'Bob';
say "ok # File operations";
unlink $tempfile;

# Static interface tests
use YAML::PP qw(Load Dump LoadFile DumpFile);
@docs = Load("key: value\n");
print "not " unless $docs[0]->{key} eq 'value';
say "ok # Static Load";

$yaml = Dump({ test => 'static' });
print "not " unless $yaml =~ /test:/;
say "ok # Static Dump";

# Complex nested structures
my $complex = {
    array_of_hashes => [
        { id => 1, data => 'first' },
        { id => 2, data => 'second' }
    ],
    hash_of_arrays => {
        numbers => [1, 2, 3],
        letters => ['a', 'b', 'c']
    }
};

$yaml = $ypp->dump_string($complex);
my $reloaded = ($ypp->load_string($yaml))[0];
print "not " unless 
    $reloaded->{array_of_hashes}[1]{data} eq 'second' &&
    $reloaded->{hash_of_arrays}{letters}[2] eq 'c';
say "ok # Complex structure roundtrip";

# Data types
my $types = {
    string => "hello",
    integer => 42,
    float => 3.14,
    boolean => JSON::true(),
    undef => undef,
};

$yaml = $ypp->dump_string($types);
my $loaded_types = ($ypp->load_string($yaml))[0];
print "not " unless 
    $loaded_types->{integer} == 42 &&
    $loaded_types->{float} > 3.13 &&
    $loaded_types->{float} < 3.15;
say "ok # Data types preservation";

# Circular reference test
my $circular = {};
$circular->{self} = $circular;
$yaml = $ypp->dump_string($circular);
my $loaded_circular = ($ypp->load_string($yaml))[0];
print "not " unless $loaded_circular->{self} == $loaded_circular;
say "ok # Circular reference handling";

# More complex circular structure
my $a = {};
my $b = { a => $a };
$a->{b} = $b;
$yaml = $ypp->dump_string($a);
my $loaded_a = ($ypp->load_string($yaml))[0];
print "not " unless $loaded_a->{b}{a} == $loaded_a;
say "ok # Complex circular reference handling";
