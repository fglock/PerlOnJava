use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 38;
use Test::Deep;
local $YAML::LoadBlessed;
$YAML::LoadBlessed = 1;

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
    cmp_deeply(
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
=== a yaml error log
+++ yaml
---
date: Sun Oct 28 20:41:17 2001
error msg: Premature end of script headers
---
date: Sun Oct 28 20:41:44 2001
error msg: malformed header from script. Bad header=</UL>
---
date: Sun Oct 28 20:42:19 2001
error msg: malformed header from script. Bad header=</UL>
+++ perl
my $a = { map {split /:\s*/, $_, 2} split /\n/, <<END };
date: Sun Oct 28 20:41:17 2001
error msg: Premature end of script headers
END
my $b = { map {split /:\s*/, $_, 2} split /\n/, <<END };
date: Sun Oct 28 20:41:44 2001
error msg: malformed header from script. Bad header=</UL>
END
my $c = { map {split /:\s*/, $_, 2} split /\n/, <<END };
date: Sun Oct 28 20:42:19 2001
error msg: malformed header from script. Bad header=</UL>
END
($a, $b, $c)
=== comments and some top level documents
+++ yaml
# Top level documents
#
# Note that inline (single line) values
# are not allowed at the top level. This
# includes implicit values, quoted values
# and inline collections.
---
a: map
---
- a
- sequence
--- >
plain scalar
--- |
This
 is
  a
   block.
    It's
    kinda
   like
  a
 here
document.
--- |-
A
 chomped
  block.
+++ perl
my $a = {a => 'map'};
my $b = ['a', 'sequence'];
my $c = "plain scalar\n";
my $d = <<END;
This
 is
  a
   block.
    It's
    kinda
   like
  a
 here
document.
END
my $e = <<END;
A
 chomped
  block.
END
chomp($e);
($a, $b, $c, $d, $e)
=== an array of assorted junk
+++ yaml
# Inline collections
#
# sequence
---
- [1,2,3]
# trailing comma is ignored
# still 3 elements
- [1,2,3,]
# four empty strings
- [,,,,]
# a pair of commas
- [",",","]
# a map
- {foo: bar,baz: too}
# empty sequence
- []
# empty map
- {}
# times for keys (key/value separator is ': ')
- {09:00:00: Breakfast, 12:00:00: lunch time,}
# a private Perl XYZ object
- !perl/XYZ {small: object}
# an object containing objects
- !perl/ABC [!perl/@DEF [a,b,c],!perl/GHI {do: re, mi: fa, so: la,ti: do}]
# sequences of self referential elements
# (inline form not working yet) :(
# - &FOO [*FOO,*FOO,*FOO]
- &FOO
 - *FOO
 - *FOO
 - *FOO
#
# sequence of maps
- [{name: Ingy},{name: Clark},{name: Oren},]
+++ perl
my $a = [1,2,3];
my $b = [1,2,3,];
my $c = ["","","","",];
my $d = [",",","];
my $e = {foo => 'bar', baz => 'too'};
my $f = [];
my $g = {};
my $h = {'09:00:00' => 'Breakfast', '12:00:00' => 'lunch time'};
my $i = bless {small => 'object'}, 'XYZ';
my $j = bless [bless([qw(a b c)], 'DEF'),
            bless({do => 're', mi => 'fa', so => 'la', ti => 'do'}, 'GHI'),
          ], 'ABC';
my $k = [];
push @$k, $k, $k, $k;
my $l = [{name => 'Ingy'}, {name => 'Clark'}, {name => 'Oren'}, ];
[$a, $b, $c, $d, $e, $f, $g, $h, $i, $j, $k, $l]
=== a bunch of small top level thingies
+++ yaml
--- 42
--- foo
--- " bar "
--- []
--- #YAML:1.0 {}
--- '#YAML:9.9'
--- {foo: [1, 2, 3], 12:34:56: bar}
+++ perl
my $a = 42;
my $b = "foo";
my $c = " bar ";
my $d = [];
my $e = {};
my $f = "#YAML:9.9";
my $g = {foo => [1, 2, 3], '12:34:56' => 'bar'};
($a, $b, $c, $d, $e, $f, $g)
=== a headerless sequence and a map
+++ yaml
- 2
- 3
- 4
--- #YAML:1.0
foo: bar
+++ perl
([2,3,4], {foo => 'bar'})
=== comments in various places
+++ yaml
     # A pre header comment
---
# comment
 # comment
                                          #comment
- 2
# comment
# comment
- 3
- 4
   # comment
- 5
# last comment
--- #YAML:1.0
boo:                          far
  # a comment
foo                  :        bar
---
- >
 # Not a comment;
# Is a comment
 #Another comment
--- 42
          #Final
         #Comment
+++ perl
([2,3,4,5],
 {foo => 'bar', boo => 'far'},
 ["# Not a comment;\n"],
 42)
=== several docs, some empty
+++ yaml
---
- foo
- bar
---
---
- foo
- foo
---
# comment

---
- bar
- bar
+++ perl
(['foo', 'bar'],undef,['foo', 'foo'],undef,['bar', 'bar'])
=== a perl reference to a scalar
+++ yaml
--- !perl/ref:
  =: 42
+++ perl
(\42);
=== date loading
+++ yaml
---
- 1964-03-25
- ! "1975-04-17"
- !date '2001-09-11'
- 12:34:00
- ! "12:00:00"
- !time '01:23:45'
+++ perl
['1964-03-25',
 '1975-04-17',
 '2001-09-11',
 '12:34:00',
 '12:00:00',
 '01:23:45',
];
=== sequence with trailing comment
+++ yaml
---
- fee
- fie
- foe
# no num defined
+++ perl
[qw(fee fie foe)]
=== a simple literal block
+++ yaml
---
- |
  foo
  bar

+++ perl
["foo\nbar\n"]
=== an unchomped literal
+++ yaml -trim
---
- |+
  foo
  bar

+++ perl
["foo\nbar\n\n"]
=== a chomped literal
+++ yaml -trim
---
- |-
  foo
  bar

+++ perl
["foo\nbar"]
=== assorted numerics
+++ yaml
---
#- -
#- +
- 44
- -45
- 4.6
- -4.7
- 3e+2
- [-4e+3, 5e-4]
- -6e-10
- 2001-12-15
- 2001-12-15T02:59:43.1Z
- 2001-12-14T21:59:43.25-05:00
+++ perl
[44, -45, 4.6, -4.7, '3e+2', ['-4e+3', '5e-4'], '-6e-10',
 '2001-12-15', '2001-12-15T02:59:43.1Z', '2001-12-14T21:59:43.25-05:00',
]
=== an empty string top level doc
+++ yaml
---
+++ perl
undef

=== an array of various undef
+++ yaml
---
-
-
- ''
+++ perl
[undef,undef,'']
=== !!perl/array
+++ yaml
--- !!perl/array
- 1
+++ perl
[ 1 ]
=== !!perl/array:
+++ yaml
--- !!perl/array:
- 1
+++ perl
[ 1 ]
=== !!perl/array:moose
+++ yaml
--- !!perl/array:moose
- 1
+++ perl
bless([ 1 ], "moose")
=== foo
+++ yaml
--- !!perl/hash
foo: bar
+++ perl
{ foo => "bar" }
=== foo
+++ yaml
--- !!perl/hash:
foo: bar
+++ perl
{ foo => "bar" }
=== foo
+++ yaml
--- !!perl/array:moose
foo: bar
+++ perl
bless({ foo => "bar" }, "moose")
=== foo
+++ yaml
--- !!perl/ref
=: 1
+++ perl
\1
=== foo
+++ yaml
--- !!perl/ref:
=: 1
+++ perl
\1
=== foo
+++ yaml
--- !!perl/ref:moose
=: 1
+++ perl
bless(do { my $x = 1; \$x}, "moose")
=== foo
+++ yaml
--- !!perl/scalar 1
+++ perl
1
=== foo
+++ yaml
--- !!perl/scalar: 1
+++ perl
1
=== foo
+++ yaml
--- !!perl/scalar:moose 1
+++ perl
bless(do { my $x = 1; \$x}, "moose")
=== ^ can start implicit
+++ yaml
- ^foo
+++ perl
['^foo']
=== Quoted keys
+++ yaml
- 'test - ': 23
  'test '' ': 23
  "test \\": 23
+++ perl
[{ 'test - ' => 23, "test ' " => 23, 'test \\' => 23 }]
=== Plain string with multiple spaces
+++ yaml
--- A       B
+++ perl
'A       B'
=== Plain string with multiple spaces at the beginning
+++ yaml
--- "   ABC"
+++ perl
'   ABC'
=== Allowed characters in anchors
+++ yaml
---
- &a.1 a
- &b/2 b
- &c_3 c
- &d-4 d
- *a.1
- *b/2
- *c_3
- *d-4
+++ perl
['a', 'b', 'c', 'd', 'a', 'b', 'c', 'd']

=== Compact nested block sequences
+++ yaml
- - a
  -  b
  - - 1
  -  - 2
     - 3
- - [c]
+++ perl
[
    ['a', 'b', [1], [2,3] ],
    [ ['c'] ],
]

=== Combined block scalar indicators
+++ yaml
---
a: |-2
    1
  2
b: |2-
    1
  2
c: >+2
    1
  2
d: >2+
    1
  2
+++ perl
{
    a => "  1\n2",
    b => "  1\n2",
    c => "  1\n2\n",
    d => "  1\n2\n",
}

=== Nested explicit key
+++ yaml
---
- ? a
  : b
+++ perl
[{ a => 'b' }]

=== Nested mappings with non \w keys
+++ yaml
---
- .: a
  <: b
  -: c
- 'not: a map'
- "not: a map"
+++ perl
[ { '.' => 'a', '<' => 'b', '-' => 'c' }, 'not: a map', 'not: a map' ]

=== Zero indented block sequence
+++ yaml
a:
  b:
  -
  -
c:
 -
 -
d:
- 1
- 2
e:
  - 3
  - 4
  - f:
    - 5
    - 6
    g: 7
+++ perl
{
    a => { b => [ undef, undef ] },
    c => [undef, undef],
    d => [1, 2],
    e => [3, 4, {
        f => [5, 6],
        g => 7,
    }],
}

