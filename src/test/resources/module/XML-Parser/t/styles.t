use strict;
use warnings;
use Test::More;
use XML::Parser;
use IO::File;

# ===== Debug style =====

{
    my $parser = XML::Parser->new( Style => 'Debug' );
    isa_ok( $parser, 'XML::Parser', 'Debug parser created' );

    # Capture STDERR output
    my $tmpfile = IO::File->new_tmpfile();
    open( my $olderr, '>&', \*STDERR ) or die "Cannot dup STDERR: $!";
    open( STDERR, '>&', $tmpfile->fileno ) or die "Cannot redirect STDERR: $!";

    $parser->parse('<foo>bar</foo>');

    open( STDERR, '>&', $olderr ) or die "Cannot restore STDERR: $!";
    close $olderr;

    seek( $tmpfile, 0, 0 );
    my @lines = <$tmpfile>;
    chomp @lines;

    is( scalar @lines, 3, 'Debug: three lines of output' );

    # Start tag line: Context is empty for root, so " \\ ()"
    like( $lines[0], qr/\\\\.*\(\)/, 'Debug: start tag format' );

    # Char line: "foo || bar" — Context has ['foo'] during char data
    like( $lines[1], qr/foo\s+\|\|\s+bar/, 'Debug: char data format' );

    # End tag line: Context is empty for root close, so " //"
    like( $lines[2], qr/\/\//, 'Debug: end tag format' );
}

# Debug with attributes
{
    my $tmpfile = IO::File->new_tmpfile();
    open( my $olderr, '>&', \*STDERR ) or die "Cannot dup STDERR: $!";
    open( STDERR, '>&', $tmpfile->fileno ) or die "Cannot redirect STDERR: $!";

    my $p = XML::Parser->new( Style => 'Debug' );
    $p->parse('<root attr="val"/>');

    open( STDERR, '>&', $olderr ) or die "Cannot restore STDERR: $!";
    close $olderr;

    seek( $tmpfile, 0, 0 );
    my @lines = <$tmpfile>;
    chomp @lines;

    like( $lines[0], qr/attr\b.*\bval\b/, 'Debug: attributes appear in start tag' );
}

# ===== Objects style =====

{
    my $parser = XML::Parser->new( Style => 'Objects', Pkg => 'TestObj' );
    isa_ok( $parser, 'XML::Parser', 'Objects parser created' );

    my $tree = $parser->parse('<root><child>text</child></root>');
    ok( $tree, 'Objects: parse returns result' );
    is( ref($tree), 'ARRAY', 'Objects: result is array ref' );
    is( scalar @$tree, 1, 'Objects: one root element' );

    my $root = $tree->[0];
    isa_ok( $root, 'TestObj::root', 'Objects: root blessed into correct class' );
    ok( exists $root->{Kids}, 'Objects: root has Kids property' );
    is( ref( $root->{Kids} ), 'ARRAY', 'Objects: Kids is array ref' );

    my $child = $root->{Kids}[0];
    isa_ok( $child, 'TestObj::child', 'Objects: child blessed into correct class' );

    my $text = $child->{Kids}[0];
    isa_ok( $text, 'TestObj::Characters', 'Objects: text node class' );
    is( $text->{Text}, 'text', 'Objects: text content correct' );
}

# Objects with attributes
{
    my $tree = XML::Parser->new( Style => 'Objects', Pkg => 'AttrObj' )
        ->parse('<item id="42" name="foo"/>');

    my $item = $tree->[0];
    isa_ok( $item, 'AttrObj::item', 'Objects: element with attrs' );
    is( $item->{id},   '42',  'Objects: attribute id' );
    is( $item->{name}, 'foo', 'Objects: attribute name' );
}

# Objects text concatenation (adjacent character data merged)
{
    # Expat may split text across multiple Char callbacks;
    # Objects style should merge them into one Characters node.
    my $tree = XML::Parser->new( Style => 'Objects', Pkg => 'MergeObj' )
        ->parse('<r>hello</r>');

    my @kids = @{ $tree->[0]{Kids} };
    is( scalar @kids, 1, 'Objects: single text child (no splitting)' );
    is( $kids[0]{Text}, 'hello', 'Objects: merged text content' );
}

# ===== Tree style =====

{
    my $parser = XML::Parser->new( Style => 'Tree' );
    isa_ok( $parser, 'XML::Parser', 'Tree parser created' );

    my $tree = $parser->parse('<foo>bar</foo>');
    is( ref($tree),             'ARRAY', 'Tree: result is array ref' );
    is( $tree->[0],             'foo',   'Tree: root tag name' );
    is( ref( $tree->[1] ),      'ARRAY', 'Tree: root content is array ref' );
    is( ref( $tree->[1][0] ),   'HASH',  'Tree: attributes hash present' );
    is( $tree->[1][1],          '0',     'Tree: text pseudo-tag' );
    is( $tree->[1][2],          'bar',   'Tree: text content' );
}

# Tree with attributes
{
    my $tree = XML::Parser->new( Style => 'Tree' )
        ->parse('<item id="1" class="x"/>');

    is( $tree->[0], 'item', 'Tree: element name' );
    my $attrs = $tree->[1][0];
    is( $attrs->{id},    '1', 'Tree: attribute id' );
    is( $attrs->{class}, 'x', 'Tree: attribute class' );
}

# Tree with nested elements
{
    my $tree = XML::Parser->new( Style => 'Tree' )
        ->parse('<a><b>one</b><c>two</c></a>');

    is( $tree->[0], 'a', 'Tree nested: root tag' );
    my $content = $tree->[1];
    is( ref( $content->[0] ), 'HASH', 'Tree nested: attrs hash' );

    # content: [{}, 'b', [...], 'c', [...]]
    is( $content->[1], 'b',   'Tree nested: first child tag' );
    is( $content->[2][1], '0', 'Tree nested: first child text pseudo-tag' );
    is( $content->[2][2], 'one', 'Tree nested: first child text' );
    is( $content->[3], 'c',   'Tree nested: second child tag' );
    is( $content->[4][1], '0', 'Tree nested: second child text pseudo-tag' );
    is( $content->[4][2], 'two', 'Tree nested: second child text' );
}

# Tree with mixed content
{
    my $tree = XML::Parser->new( Style => 'Tree' )
        ->parse('<p>Hello <b>world</b>!</p>');

    my $content = $tree->[1];
    # [{}, 0, "Hello ", "b", [{}, 0, "world"], 0, "!"]
    is( $content->[1], '0',       'Tree mixed: leading text pseudo-tag' );
    is( $content->[2], 'Hello ',  'Tree mixed: leading text' );
    is( $content->[3], 'b',       'Tree mixed: inline element' );
    is( $content->[5], '0',       'Tree mixed: trailing text pseudo-tag' );
    is( $content->[6], '!',       'Tree mixed: trailing text' );
}

# ===== Stream style =====

{
    my $parser = XML::Parser->new( Style => 'Stream', Pkg => 'StreamTest' );
    isa_ok( $parser, 'XML::Parser', 'Stream parser created' );

    my @events;
    {
        package StreamTest;
        no warnings 'once';

        sub StartDocument { push @events, ['StartDocument'] }
        sub StartTag      { push @events, ['StartTag', $_[1], $_] }
        sub Text          { push @events, ['Text', $_] }
        sub EndTag        { push @events, ['EndTag', $_[1], $_] }
        sub EndDocument   { push @events, ['EndDocument'] }
    }
    package main;

    $parser->parse('<msg>hello</msg>');

    is( $events[0][0], 'StartDocument', 'Stream: StartDocument called' );
    is( $events[1][0], 'StartTag',      'Stream: StartTag called' );
    is( $events[1][1], 'msg',           'Stream: StartTag element name' );
    like( $events[1][2], qr/^<msg>$/,   'Stream: StartTag $_ is tag string' );
    is( $events[2][0], 'Text',          'Stream: Text called' );
    is( $events[2][1], 'hello',         'Stream: Text $_ has content' );
    is( $events[3][0], 'EndTag',        'Stream: EndTag called' );
    is( $events[3][1], 'msg',           'Stream: EndTag element name' );
    like( $events[3][2], qr{^</msg>$},  'Stream: EndTag $_ is close tag' );
    is( $events[4][0], 'EndDocument',   'Stream: EndDocument called' );
}

# Stream with attributes — %_ populated
{
    my @captured_attrs;
    {
        package StreamAttrTest;
        no warnings 'once';
        sub StartTag { push @captured_attrs, {%_} }
        sub EndTag   { }    # suppress default print
        sub Text     { }    # suppress default print
    }
    package main;

    XML::Parser->new( Style => 'Stream', Pkg => 'StreamAttrTest' )
        ->parse('<item key="val" num="3"/>');

    is( $captured_attrs[0]{key}, 'val', 'Stream: %_ has attribute key' );
    is( $captured_attrs[0]{num}, '3',   'Stream: %_ has attribute num' );
}

# ===== Subs style =====

{
    my $parser = XML::Parser->new( Style => 'Subs', Pkg => 'SubsTest' );
    isa_ok( $parser, 'XML::Parser', 'Subs parser created' );

    my @events;
    {
        package SubsTest;
        no warnings 'once';
        sub item  { push @events, ['start', $_[1], @_[2..$#_]] }
        sub item_ { push @events, ['end',   $_[1]] }
    }
    package main;

    $parser->parse('<item color="red">data</item>');

    is( $events[0][0], 'start',  'Subs: start handler called' );
    is( $events[0][1], 'item',   'Subs: start tag name' );
    is( $events[0][2], 'color',  'Subs: attribute name passed' );
    is( $events[0][3], 'red',    'Subs: attribute value passed' );
    is( $events[1][0], 'end',    'Subs: end handler called' );
    is( $events[1][1], 'item',   'Subs: end tag name' );
}

# Subs: missing handlers silently skipped
{
    my $parser = XML::Parser->new( Style => 'Subs', Pkg => 'SubsEmpty' );
    my $result = eval { $parser->parse('<unknown/>'); 1 };
    ok( $result, 'Subs: missing handler does not croak' );
}

done_testing;
