use strict;
use lib -e 't' ? 't' : 'test';
use TestYAML tests => 11;
use Test::Deep;
use YAML ();

my $unblessed = YAML::Load(<<"EOM");
--- !!perl/array:Foo []
EOM
is(ref $unblessed, 'ARRAY', "No objects by default");

$YAML::LoadBlessed = 0;

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

{
    local $YAML::LoadCode = 1;
    my $data = YAML::Load(<<'EOM');
--- !!perl/code:Foo::Bar |
{
    return $_[0] * 2
}
EOM
    my $ref = ref $data;
    cmp_ok($ref, 'eq', 'CODE', "Coderef loaded, but not blessed");
    my $result = $data->(2);
    cmp_ok($result, 'eq', 4, "Coderef works");
}

{
    $main::foo = 23;
    my $data = YAML::Load(<<'EOM');
--- !!perl/glob:moose
  PACKAGE: main
  NAME: foo
  SCALAR: 42
EOM
    my $ref = ref $data;
    cmp_ok($main::foo, '==', 23, "Glob did not set variable");
}

__DATA__
=== an array of assorted junk
+++ yaml
---
# a private Perl XYZ object
- !perl/XYZ {small: object}
# an object containing objects
- !perl/ABC [!perl/@DEF [a,b,c],!perl/GHI {do: re, mi: fa, so: la,ti: do}]
+++ perl
my $i = {small => 'object'};
my $j = [[qw(a b c)],
            {do => 're', mi => 'fa', so => 'la', ti => 'do'},
          ];
[ $i, $j ]
=== !!perl/array:moose
+++ yaml
--- !!perl/array:moose
- 1
+++ perl
[ 1 ]
=== !!perl/hash:moose
+++ yaml
--- !!perl/hash:moose
foo: bar
+++ perl
{ foo => "bar" }
=== !!perl/ref:moose
+++ yaml
--- !!perl/ref:moose
=: 1
+++ perl
do { my $x = 1; \$x}
=== !!perl/scalar:moose
+++ yaml
--- !!perl/scalar:moose 1
+++ perl
do { my $x = 1; \$x}
=== !!perl/regexp:moose
+++ yaml
--- !!perl/regexp:moose (?-xism:foo$)
+++ perl
qr{foo$}
=== !!perl/glob:moose
+++ yaml
--- !!perl/glob:moose
  PACKAGE: main
  NAME: foo
  SCALAR: 0
+++ perl
*main::foo
