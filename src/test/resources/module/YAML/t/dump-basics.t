use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 7;

filters {
    perl => [qw'eval yaml_dump'],
};

run_is;

__DATA__
=== A map
+++ perl
+{ foo => 'bar', baz => 'boo' }
+++ yaml
---
baz: boo
foo: bar

=== A list
+++ perl
[ qw'foo bar baz' ]
+++ yaml
---
- foo
- bar
- baz

=== A List of maps
+++ perl
[{ foo => 42, bar => 44}, {one => 'two', three => 'four'}]
+++ yaml
---
- bar: 44
  foo: 42
- one: two
  three: four

=== A map of lists
+++ perl
+{numbers => [ 5..7 ], words => [qw'five six seven']}
+++ yaml
---
numbers:
  - 5
  - 6
  - 7
words:
  - five
  - six
  - seven

=== Top level scalar
+++ perl: 'The eagle has landed'
+++ yaml
--- The eagle has landed

=== Top level literal scalar
+++ perl
<<'...'
sub foo {
    return "Don't eat the foo";
}
...
+++ yaml
--- |
sub foo {
    return "Don't eat the foo";
}

=== Single Dash
+++ perl: {foo => '-'}
+++ yaml
---
foo: '-'
