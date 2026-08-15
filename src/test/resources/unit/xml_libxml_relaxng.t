use strict;
use warnings;
use Test::More;

eval { require XML::LibXML; 1 }
    or plan skip_all => 'XML::LibXML is not installed';

my $schema = XML::LibXML::RelaxNG->new(string => <<'RNG');
<element xmlns="http://relaxng.org/ns/structure/1.0" name="root">
  <element name="item"><text/></element>
</element>
RNG

ok($schema, 'compiled a RELAX NG schema');
my $parser = XML::LibXML->new;
is($schema->validate($parser->parse_string('<root><item>ok</item></root>')), 0,
   'valid document returns zero');
my $valid = eval { $schema->validate($parser->parse_string('<root><wrong/></root>')); 1 };
ok(!$valid, 'invalid document throws');
like($@, qr/(?:expect|allow|valid|element)/i, 'validation error is meaningful');

done_testing;
