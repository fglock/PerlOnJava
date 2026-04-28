use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 4;

filters {
    yaml => [yaml => 'dumper'],
    perl => [strict => eval => 'dumper'],
};

run_is yaml => 'perl';

__END__
=== A simple map
+++ yaml
---
one: foo
two: bar
three: baz
+++ perl
+{qw(one foo two bar three baz)}


=== Common String Types
+++ yaml
---
one: simple string
two: 42
three: '1 Single Quoted String'
four: "YAML's Double Quoted String"
five: |
  A block
    with several
      lines.
six: |-
  A "chomped" block
seven: >
  A
  folded
   string
+++ perl
{
    one => "simple string",
    two => '42',
    three => "1 Single Quoted String",
    four => "YAML's Double Quoted String",
    five => "A block\n  with several\n    lines.\n",
    six => 'A "chomped" block',
    seven => "A folded\n string\n",
}


=== Multiple documents
+++ yaml
---
foo: bar
---
bar: two
+++ perl
+{qw(foo bar)}, {qw(bar two)};


=== Comments
+++ yaml
# Leading Comment
---
# Preceding Comment
foo: bar
# Two
# Comments
---
    # Indented comment
bar: two
bee: three
# Intermediate comment
bore: four
+++ perl
+{qw(foo bar)}, {qw(bar two bee three bore four)}
