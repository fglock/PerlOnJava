use Test::More;
use YAML;

YAML::Load("a: b");
YAML::Load("a:\n  b: c");
YAML::Load("a: b\nc: d");

pass "YAML w/o final newlines loads";

done_testing;
