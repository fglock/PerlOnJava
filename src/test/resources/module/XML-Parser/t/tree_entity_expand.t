use Test::More tests => 7;
use XML::Parser;

# GH#74: Document that predefined XML entities (&lt; &gt; &amp; &quot; &apos;)
# are always expanded by Expat in Tree style output. This is required by the
# XML specification and cannot be prevented.

my $p = XML::Parser->new( Style => 'Tree' );

{
    my $tree = $p->parse('<root>a &lt; b &gt; c</root>');
    is( $tree->[1][2], 'a < b > c', '&lt; and &gt; expanded in text' );
}

{
    my $tree = $p->parse('<root>foo &amp; bar</root>');
    is( $tree->[1][2], 'foo & bar', '&amp; expanded in text' );
}

{
    my $tree = $p->parse('<root>&quot;quoted&quot;</root>');
    is( $tree->[1][2], '"quoted"', '&quot; expanded in text' );
}

{
    my $tree = $p->parse('<root>it&apos;s</root>');
    is( $tree->[1][2], "it's", '&apos; expanded in text' );
}

{
    my $tree = $p->parse('<root>&lt;&amp;&gt;</root>');
    is( $tree->[1][2], '<&>', 'multiple entities expanded together' );
}

{
    my $tree = $p->parse('<root attr="a &lt; b">text</root>');
    is( $tree->[1][0]{attr}, 'a < b', 'entities expanded in attribute values' );
}

{
    my $tree = $p->parse('<root>no entities here</root>');
    is( $tree->[1][2], 'no entities here', 'text without entities unchanged' );
}
