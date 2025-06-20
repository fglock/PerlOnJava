use feature 'say';
use strict;
use Test::More;
use YAML::PP;
use JSON;

# Basic OO interface tests
my $ypp = YAML::PP->new(
    cyclic_refs => "allow",
    schema => ['JSON', 'Perl']  # Enable Perl schema to handle references
);
ok(defined $ypp, 'YAML::PP constructor');

# Simple scalar mapping
my $yaml = "name: Alice\nage: 25\n";
my @docs = $ypp->load_string($yaml);
is($docs[0]->{name}, 'Alice', 'Name matches in basic mapping');
is($docs[0]->{age}, 25, 'Age matches in basic mapping');

# Array/sequence test
$yaml = "- apple\n- banana\n- cherry\n";
@docs = $ypp->load_string($yaml);
is($docs[0]->[0], 'apple', 'First sequence element');
is($docs[0]->[2], 'cherry', 'Last sequence element');

# Nested structures
$yaml = "outer:\n  inner:\n    value: 42\n";
@docs = $ypp->load_string($yaml);
is($docs[0]->{outer}{inner}{value}, 42, 'Nested structure value');

# Multiple documents
$yaml = "---\nfirst: 1\n---\nsecond: 2\n";
@docs = $ypp->load_string($yaml);
is(scalar @docs, 2, 'Correct number of documents');
is($docs[0]->{first}, 1, 'First document value');
is($docs[1]->{second}, 2, 'Second document value');

# Dump simple hash
my $data = { name => 'Bob', numbers => [1, 2, 3] };
$yaml = $ypp->dump_string($data);
my @parsed = $ypp->load_string($yaml);
is($parsed[0]->{name}, 'Bob', 'Name preserved in dump/load');
is($parsed[0]->{numbers}[1], 2, 'Array element preserved in dump/load');

# Test file operations
my $tempfile = "test_yaml_$.yml";
$ypp->dump_file($tempfile, $data);
@docs = $ypp->load_file($tempfile);
is($docs[0]->{name}, 'Bob', 'File operations preserve data');
unlink $tempfile;

# Static interface tests
use YAML::PP qw(Load Dump LoadFile DumpFile);
@docs = Load("key: value\n");
is($docs[0]->{key}, 'value', 'Static Load works');

$yaml = Dump({ test => 'static' });
like($yaml, qr/test:/, 'Static Dump produces YAML');

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
is($reloaded->{array_of_hashes}[1]{data}, 'second', 'Complex structure array access');
is($reloaded->{hash_of_arrays}{letters}[2], 'c', 'Complex structure hash access');

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
is($loaded_types->{integer}, 42, 'Integer preservation');
cmp_ok($loaded_types->{float}, '>', 3.13, 'Float greater than check');
cmp_ok($loaded_types->{float}, '<', 3.15, 'Float less than check');

# Circular reference test
my $circular = {};
$circular->{self} = $circular;
$yaml = $ypp->dump_string($circular);
my $loaded_circular = ($ypp->load_string($yaml))[0];
is($loaded_circular->{self}, $loaded_circular, 'Simple circular reference');

# More complex circular structure
my $a = {};
my $b = { a => $a };
$a->{b} = $b;
$yaml = $ypp->dump_string($a);
my $loaded_a = ($ypp->load_string($yaml))[0];
is($loaded_a->{b}{a}, $loaded_a, 'Complex circular reference');

done_testing();