use strict;
use warnings;
use Test::More tests => 6;

my $globref = \do { local *FH };
is(ref(\*$globref), 'GLOB', 'anonymous glob reference dereferences under strict refs');

my $object = bless $globref, 'Local::GlobHandle';
is(ref(\*$object), 'GLOB', 'blessed anonymous glob reference remains dereferenceable');
ok(UNIVERSAL::isa($object, 'GLOB'), 'blessed anonymous glob reports its underlying ref type');

*$object->{marker} = 42;
is(*$object->{marker}, 42, 'blessed anonymous glob exposes its hash slot');

{
    package Local::GlobHandle;
    sub TIEHANDLE { $_[1] }
}

ok(tie(*$object, 'Local::GlobHandle', $object), 'blessed anonymous glob can be tied');
is(ref(tied(*$object)), 'Local::GlobHandle', 'tied handle retains the supplied object');
