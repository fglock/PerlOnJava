use strict;
my $t; use lib ($t = -e 't' ? 't' : 'test');
use TestYAML tests => 3;

my $test_file = "$t/svk-config.yaml";
my $node = LoadFile($test_file);

is ref($node), 'HASH',
    "loaded svk file is a hash";

open IN, $test_file or die "Can't open $test_file for input: $!";
my $yaml_from_file = do {local $/; <IN>};

like $yaml_from_file, qr{^---\ncheckout: !perl/Data::Hierarchy\n},
    "at least first two lines of file are right";

my $yaml_from_node = Dump($node);

is Dump(Load($yaml_from_node)), Dump(Load($yaml_from_file)),
    "svk data roundtrips!";;
