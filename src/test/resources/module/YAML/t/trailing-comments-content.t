use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 6;

run {
    my $block = shift;
    my @result = eval {
        Load($block->yaml)
    };
    my $error1 = $@ || '';
    if ( $error1 ) {
        # $error1 =~ s{line: (\d+)}{"line: $1   ($0:".($1+$test->{lines}{yaml}-1).")"}e;
    }
    my @expect = eval $block->perl;
    my $error2 = $@ || '';
    if (my $errors = $error1 . $error2) {
        fail($block->description
              . $errors);
        next;
    }
    is_deeply(
        \@result,
        \@expect,
        $block->description,
    ) or do {
        require Data::Dumper;
        diag("Wanted: ".Data::Dumper::Dumper(\@expect));
        diag("Got: ".Data::Dumper::Dumper(\@result));
    }
};

__DATA__

=== Comment after simple mapping value
+++ yaml
---
foo: val #comment val
+++ perl
{ foo => "val" }

=== Comment after simple sequence value
+++ yaml
---
foo:
 - s2 #comment s2
+++ perl
{ foo => ['s2'] }

=== Comment after simple sequence value (2)
+++ yaml
---
- s2 #comment s1
+++ perl
['s2']

=== Comment after simple top level scalar
+++ yaml
--- abc # comment abc
+++ perl
'abc'

=== Comment after empty mapping value
+++ yaml
---
foo:  #comment foo
bar: #comment bar
+++ perl
{ foo => undef, bar => undef }

=== Comment after empty sequence value
+++ yaml
---
foo:
 - # empty sequence value
+++ perl
{ foo => [''] }
