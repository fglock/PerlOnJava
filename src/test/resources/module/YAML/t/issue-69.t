use Test::More tests => 2;
use YAML;

my $seq = eval { YAML::Load("foo: [bar] "); 1 };
my $map = eval { YAML::Load("foo: {bar: 42}  "); 1 };

ok($seq, "YAML inline sequence with trailing space loads");
ok($map, "YAML inline mapping with trailing space loads");

done_testing;
