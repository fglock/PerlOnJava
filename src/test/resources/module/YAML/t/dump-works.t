use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML;

run_is;

sub yaml_dump {
    return Dump(@_);
}

__DATA__
=== A one key hash
+++ perl eval yaml_dump
+{foo => 'bar'}
+++ yaml
---
foo: bar
