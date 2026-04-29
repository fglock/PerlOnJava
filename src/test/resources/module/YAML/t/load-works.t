use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML;

filters {
    perl => 'eval',
    yaml => 'yaml_load',
};

run_is_deeply;

__DATA__
=== A one key hash
+++ perl
+{foo => 'bar'}
+++ yaml
---
foo: bar
=== empty hashes
+++ perl
+{foo1 => undef, foo2 => undef}
+++ yaml
foo1:
foo2:
