use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 6;
use YAML ();
use YAML::Dumper;

$YAML::QuoteNumericStrings = 1;
filters { perl => [qw'eval yaml_dump'], };

ok( YAML::Dumper->is_literal_number(1),    '1 is a literal number' );
ok( !YAML::Dumper->is_literal_number("1"), '"1" is not a literal number' );
ok( YAML::Dumper->is_literal_number( "1" + 1 ), '"1" +1  is a literal number' );

run_is;

__DATA__
=== Mixed Literal and Stringy ints
+++ perl
+{ foo => '2', baz => 1 }
+++ yaml
---
baz: 1
foo: '2'

=== Mixed Literal and Stringy floats
+++ perl
+{ foo => '2.000', baz => 1.000 }
+++ yaml
---
baz: 1
foo: '2.000'

=== Numeric Keys
+++ perl
+{ 10 => '2.000', 20 => 1.000, '030' => 2.000 }
+++ yaml
---
'030': 2
'10': '2.000'
'20': 1

