use strict;
use warnings;

use Test::More;
use XML::Parser;

# UTF-8 encoded test string: "café élève" (contains accented chars)
# é = U+00E9, è = U+00E8
my $cafe  = "caf\xc3\xa9";       # "café" in UTF-8 bytes
my $eleve = "\xc3\xa9l\xc3\xa8ve"; # "élève" in UTF-8 bytes

# Build a UTF-8 XML document with non-ASCII text in content and attributes
my $xml = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
    . qq(<doc attr="$cafe">$eleve</doc>);
utf8::downgrade($xml);    # ensure raw bytes, not upgraded

# ===== Char handler: UTF-8 flag on character data =====
{
    my $got_text = '';
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $got_text .= $_[1] } },
    );
    $p->parse($xml);

    ok( utf8::is_utf8($got_text),
        'Char handler: text has UTF-8 flag' );
    is( length($got_text), 5,
        'Char handler: length is 5 characters (not 7 bytes)' );
    is( $got_text, "\x{e9}l\x{e8}ve",
        'Char handler: text matches expected Unicode string' );
}

# ===== Start handler: UTF-8 flag on attribute values =====
{
    my %attrs;
    my $p = XML::Parser->new(
        Handlers => {
            Start => sub { shift; shift; %attrs = @_ },
        },
    );
    $p->parse($xml);

    ok( utf8::is_utf8( $attrs{attr} ),
        'Start handler: attribute value has UTF-8 flag' );
    is( $attrs{attr}, "caf\x{e9}",
        'Start handler: attribute value matches expected Unicode string' );
}

# ===== Tree style: UTF-8 preserved in tree structure =====
{
    my $p    = XML::Parser->new( Style => 'Tree' );
    my $tree = $p->parse($xml);

    # Tree: ['doc', [{attr => "café"}, 0, "élève"]]
    my $tree_attrs = $tree->[1][0];
    my $tree_text  = $tree->[1][2];

    ok( utf8::is_utf8($tree_text),
        'Tree style: text content has UTF-8 flag' );
    is( $tree_text, "\x{e9}l\x{e8}ve",
        'Tree style: text content matches expected' );
    ok( utf8::is_utf8( $tree_attrs->{attr} ),
        'Tree style: attribute value has UTF-8 flag' );
    is( $tree_attrs->{attr}, "caf\x{e9}",
        'Tree style: attribute value matches expected' );
}

# ===== Objects style: UTF-8 preserved in objects =====
{
    my $p    = XML::Parser->new( Style => 'Objects', Pkg => 'TestObj' );
    my $tree = $p->parse($xml);
    my $obj  = $tree->[0];
    my $kid  = $obj->{Kids}[0];

    ok( utf8::is_utf8( $kid->{Text} ),
        'Objects style: text has UTF-8 flag' );
    is( $kid->{Text}, "\x{e9}l\x{e8}ve",
        'Objects style: text matches expected' );
}

# ===== Stream style: UTF-8 preserved in accumulated text =====
{
    my $stream_text = '';

    no strict 'refs';    ## no critic
    no warnings 'once';
    local *StreamTest::Text = sub { $stream_text .= $_ };
    local *StreamTest::StartTag  = sub { };
    local *StreamTest::EndTag    = sub { };

    my $p = XML::Parser->new( Style => 'Stream', Pkg => 'StreamTest' );
    $p->parse($xml);

    ok( utf8::is_utf8($stream_text),
        'Stream style: accumulated text has UTF-8 flag' );
    is( $stream_text, "\x{e9}l\x{e8}ve",
        'Stream style: accumulated text matches expected' );
}

# ===== recognized_string: returns UTF-8 flagged string =====
{
    my $rec;
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $rec = $_[0]->recognized_string() } },
    );
    $p->parse($xml);

    ok( defined($rec) && utf8::is_utf8($rec),
        'recognized_string: has UTF-8 flag' );
}

# ===== Multi-chunk character data accumulation =====
{
    # Large payload to force multiple Char handler calls
    my $chunk   = "caf\xc3\xa9 ";    # "café " = 5 chars
    my $big_xml = qq(<?xml version="1.0" encoding="UTF-8"?>\n<doc>)
        . ( $chunk x 1000 ) . qq(</doc>);
    utf8::downgrade($big_xml);

    my $accumulated = '';
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $accumulated .= $_[1] } },
    );
    $p->parse($big_xml);

    ok( utf8::is_utf8($accumulated),
        'Multi-chunk: accumulated text has UTF-8 flag' );
    is( length($accumulated), 5000,
        'Multi-chunk: length is 5000 characters' );
}

# ===== Characters above U+00FF (multi-byte UTF-8) =====
{
    # U+4E16 (世) = \xe4\xb8\x96, U+754C (界) = \xe7\x95\x8c
    my $cjk_xml = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
        . qq(<doc>\xe4\xb8\x96\xe7\x95\x8c</doc>);
    utf8::downgrade($cjk_xml);

    my $got = '';
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $got .= $_[1] } },
    );
    $p->parse($cjk_xml);

    ok( utf8::is_utf8($got),
        'CJK text: has UTF-8 flag' );
    is( length($got), 2,
        'CJK text: length is 2 characters' );
    is( $got, "\x{4e16}\x{754c}",
        'CJK text: matches expected Unicode string' );
}

# ===== Default handler: UTF-8 flag preserved =====
{
    my $default_text = '';
    my $p = XML::Parser->new(
        Handlers => { Default => sub { $default_text .= $_[1] } },
    );
    $p->parse($xml);

    ok( utf8::is_utf8($default_text),
        'Default handler: text has UTF-8 flag' );
    like( $default_text, qr/\x{e9}l\x{e8}ve/,
        'Default handler: contains expected UTF-8 text' );
}

# ===== Comment handler: UTF-8 flag preserved =====
{
    my $xml_comment = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
        . qq(<doc><!-- caf\xc3\xa9 --></doc>);
    utf8::downgrade($xml_comment);

    my $comment_text;
    my $p = XML::Parser->new(
        Handlers => { Comment => sub { $comment_text = $_[1] } },
    );
    $p->parse($xml_comment);

    ok( utf8::is_utf8($comment_text),
        'Comment handler: text has UTF-8 flag' );
    like( $comment_text, qr/caf\x{e9}/,
        'Comment handler: contains expected UTF-8 text' );
}

# ===== Processing instruction handler: UTF-8 flag preserved =====
{
    my $xml_pi = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
        . qq(<doc><?mytarget caf\xc3\xa9?></doc>);
    utf8::downgrade($xml_pi);

    my $pi_data;
    my $p = XML::Parser->new(
        Handlers => { Proc => sub { $pi_data = $_[2] } },
    );
    $p->parse($xml_pi);

    ok( utf8::is_utf8($pi_data),
        'Proc handler: PI data has UTF-8 flag' );
    is( $pi_data, "caf\x{e9}",
        'Proc handler: PI data matches expected' );
}

# ===== CDATA section: UTF-8 preserved =====
{
    my $xml_cdata = qq(<?xml version="1.0" encoding="UTF-8"?>\n)
        . qq(<doc><![CDATA[caf\xc3\xa9]]></doc>);
    utf8::downgrade($xml_cdata);

    my $cdata_text = '';
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $cdata_text .= $_[1] } },
    );
    $p->parse($xml_cdata);

    ok( utf8::is_utf8($cdata_text),
        'CDATA: text has UTF-8 flag' );
    is( $cdata_text, "caf\x{e9}",
        'CDATA: text matches expected' );
}

done_testing();
