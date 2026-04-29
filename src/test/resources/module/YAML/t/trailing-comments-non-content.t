use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 7;

# testing trailing comments which were errors before

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

=== Comment after inline seq
+++ yaml
---
seq: [314] #comment
+++ perl
{ seq => [314] }

=== Comment after inline map
+++ yaml
---
map: {x: y} #comment
+++ perl
{ map => { x => 'y' }, }

=== Comment after literal block scalar indicator
+++ yaml
---
- |- #comment
+++ perl
['']

=== Comment after folded block scalar indicator
+++ yaml
---
- >- #comment
+++ perl
['']

=== Comment after top level literal block scalar indicator
+++ yaml
--- |- #comment
+++ perl
''
=== Comment after double quoted string
+++ yaml
---
quoted: "string" #comment
+++ perl
{ quoted => 'string' }

=== Comment after single quoted string
+++ yaml
---
quoted: 'string'  #comment
+++ perl
{ quoted => 'string' }
