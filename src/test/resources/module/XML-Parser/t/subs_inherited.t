use Test::More tests => 4;
use XML::Parser;

# Test that the Subs style only calls subs defined directly in the
# target package, not inherited methods.  Using can() would walk the
# inheritance tree, dispatching XML element names to unintended base
# class methods.  The documentation says "a sub by that name in the
# package specified by the Pkg option" — only that package.

{
    package SubsBase;
    our @called;
    sub inherited_method { push @called, 'inherited_method' }
}

{
    package SubsChild;
    our @ISA = ('SubsBase');
    our @called;
    sub root  { push @called, 'root_start' }
    sub root_ { push @called, 'root_end' }
}

# Verify that an element matching a method in SubsBase (but not
# defined in SubsChild) does NOT trigger the inherited method.
@SubsBase::called  = ();
@SubsChild::called = ();

my $p = XML::Parser->new( Style => 'Subs', Pkg => 'SubsChild' );
$p->parse('<root><inherited_method/></root>');

is_deeply(
    \@SubsChild::called,
    [ 'root_start', 'root_end' ],
    'Subs calls handlers defined in the target package'
);

is_deeply(
    \@SubsBase::called,
    [],
    'Subs does not dispatch to inherited methods'
);

# Verify that UNIVERSAL methods (isa, can, VERSION) are not called
# via element names.
{
    package SubsClean;
    our @called;
    sub root { push @called, 'root' }
}

@SubsClean::called = ();
my $p2 = XML::Parser->new( Style => 'Subs', Pkg => 'SubsClean' );
$p2->parse('<root><isa/><can/><VERSION/></root>');

is_deeply(
    \@SubsClean::called,
    ['root'],
    'UNIVERSAL methods are not dispatched via element names'
);

# Verify that the End handler also uses direct lookup (tag_)
{
    package SubsEndBase;
    our @called;
    sub cleanup_ { push @called, 'base_cleanup_end' }
}

{
    package SubsEndChild;
    our @ISA = ('SubsEndBase');
    our @called;
    sub item  { push @called, 'item_start' }
    sub item_ { push @called, 'item_end' }
}

@SubsEndBase::called  = ();
@SubsEndChild::called = ();

my $p3 = XML::Parser->new( Style => 'Subs', Pkg => 'SubsEndChild' );
$p3->parse('<item><cleanup/></item>');

is_deeply(
    \@SubsEndBase::called,
    [],
    'End handler does not dispatch to inherited methods'
);
