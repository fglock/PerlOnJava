use strict;
use warnings;
use Test::More;
use XML::Parser;

# ---------- x-sjis-unicode via XML declaration ----------

{
    my $xmldec    = "<?xml version='1.0' encoding='x-sjis-unicode' ?>\n";
    my $docstring = "<\x8e\x83>\x90\x46\x81\x41\x98\x61\x81\x41\x99\x44\n</\x8e\x83>\n";
    my $doc       = $xmldec . $docstring;

    my @bytes;
    my $lastel;

    my $p = XML::Parser->new(
        Handlers => {
            Start => sub { $lastel = $_[1] },
            Char  => sub { push @bytes, unpack( 'U0C*', $_[1] ) },
        }
    );

    $p->parse($doc);

    is( $lastel, chr(0x7949), 'x-sjis-unicode: element name decoded to U+7949' );

    my @expected = (
        0xe8, 0x89, 0xb2,    # U+8272 beauty    0x9046
        0xe3, 0x80, 0x81,    # U+3001 comma     0x8141
        0xe5, 0x92, 0x8c,    # U+548C peace     0x9861
        0xe3, 0x80, 0x81,    # U+3001 comma     0x8141
        0xe5, 0x83, 0x96,    # U+50D6 joy       0x9944
        0x0a
    );

    is_deeply( \@bytes, \@expected, 'x-sjis-unicode: character data bytes match' );

    # Same document via ProtocolEncoding (no XML declaration)
    $lastel = '';
    @bytes  = ();
    $p->parse( $docstring, ProtocolEncoding => 'X-SJIS-UNICODE' );

    is( $lastel, chr(0x7949), 'x-sjis-unicode via ProtocolEncoding: element name' );
}

# ---------- windows-1252 (Win-Latin-1) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='WINDOWS-1252' ?>\n)
      . qq(<doc euro="\x80" lsq="\x91" rdq="\x94" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'windows-1252: parse succeeded' ) or diag $@;

    is( $attr{euro}, chr(0x20AC), 'windows-1252: 0x80 -> U+20AC euro sign' );
    is( $attr{lsq},  chr(0x2018), 'windows-1252: 0x91 -> U+2018 left single quote' );
    is( $attr{rdq},  chr(0x201D), 'windows-1252: 0x94 -> U+201D right double quote' );
}

# ---------- windows-1251 (Cyrillic) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='windows-1251' ?>\n)
      . qq(<doc a="\xC0" b="\xE0" c="\xC1" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'windows-1251: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0410), 'windows-1251: 0xC0 -> U+0410 (A)' );
    is( $attr{b}, chr(0x0430), 'windows-1251: 0xE0 -> U+0430 (a)' );
    is( $attr{c}, chr(0x0411), 'windows-1251: 0xC1 -> U+0411 (B)' );
}

# ---------- koi8-r (Cyrillic) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='koi8-r' ?>\n)
      . qq(<doc a="\xC1" b="\xE1" c="\xC2" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'koi8-r: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0430), 'koi8-r: 0xC1 -> U+0430 (a)' );
    is( $attr{b}, chr(0x0410), 'koi8-r: 0xE1 -> U+0410 (A)' );
    is( $attr{c}, chr(0x0431), 'koi8-r: 0xC2 -> U+0431 (b)' );
}

# ---------- windows-1255 (Hebrew) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='windows-1255' ?>\n)
      . qq(<doc a="\xE0" b="\xE1" c="\xE2" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'windows-1255: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x05D0), 'windows-1255: 0xE0 -> U+05D0 (alef)' );
    is( $attr{b}, chr(0x05D1), 'windows-1255: 0xE1 -> U+05D1 (bet)' );
    is( $attr{c}, chr(0x05D2), 'windows-1255: 0xE2 -> U+05D2 (gimel)' );
}

# ---------- ibm866 (DOS Cyrillic) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='ibm866' ?>\n)
      . qq(<doc a="\x80" b="\x81" c="\xA0" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'ibm866: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0410), 'ibm866: 0x80 -> U+0410 (A)' );
    is( $attr{b}, chr(0x0411), 'ibm866: 0x81 -> U+0411 (B)' );
    is( $attr{c}, chr(0x0430), 'ibm866: 0xA0 -> U+0430 (a)' );
}

# ---------- iso-8859-2 (Central European) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='iso-8859-2' ?>\n)
      . qq(<doc a="\xA1" b="\xB1" c="\xC8" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'iso-8859-2: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0104), 'iso-8859-2: 0xA1 -> U+0104 (A-ogonek)' );
    is( $attr{b}, chr(0x0105), 'iso-8859-2: 0xB1 -> U+0105 (a-ogonek)' );
    is( $attr{c}, chr(0x010C), 'iso-8859-2: 0xC8 -> U+010C (C-caron)' );
}

# ---------- iso-8859-5 (Cyrillic) ----------

{
    my $doc = qq(<?xml version='1.0' encoding='iso-8859-5' ?>\n)
      . qq(<doc a="\xB0" b="\xD0" c="\xB1" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'iso-8859-5: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0410), 'iso-8859-5: 0xB0 -> U+0410 (A)' );
    is( $attr{b}, chr(0x0430), 'iso-8859-5: 0xD0 -> U+0430 (a)' );
    is( $attr{c}, chr(0x0411), 'iso-8859-5: 0xB1 -> U+0411 (B)' );
}

# ---------- iso-8859-9 (Turkish) ----------

{
    # iso-8859-9 is identical to iso-8859-1 except six characters.
    # 0xD0 -> U+011E (G-breve), 0xDD -> U+0130 (I-dot-above)
    my $doc = qq(<?xml version='1.0' encoding='iso-8859-9' ?>\n)
      . qq(<doc a="\xD0" b="\xDD" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'iso-8859-9: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x011E), 'iso-8859-9: 0xD0 -> U+011E (G-breve)' );
    is( $attr{b}, chr(0x0130), 'iso-8859-9: 0xDD -> U+0130 (I-dot-above)' );
}

# ---------- iso-8859-15 (Latin-9, euro sign) ----------

{
    # 0xA4 -> U+20AC (euro), 0xA6 -> U+0160 (S-caron), 0xA8 -> U+0161 (s-caron)
    my $doc = qq(<?xml version='1.0' encoding='iso-8859-15' ?>\n)
      . qq(<doc a="\xA4" b="\xA6" c="\xA8" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'iso-8859-15: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x20AC), 'iso-8859-15: 0xA4 -> U+20AC (euro sign)' );
    is( $attr{b}, chr(0x0160), 'iso-8859-15: 0xA6 -> U+0160 (S-caron)' );
    is( $attr{c}, chr(0x0161), 'iso-8859-15: 0xA8 -> U+0161 (s-caron)' );
}

# ---------- windows-1250 (Central European) ----------

{
    # 0x8A -> U+0160 (S-caron), 0x9A -> U+0161 (s-caron), 0xC8 -> U+010C (C-caron)
    my $doc = qq(<?xml version='1.0' encoding='windows-1250' ?>\n)
      . qq(<doc a="\x8A" b="\x9A" c="\xC8" />);

    my %attr;
    my $p = XML::Parser->new(
        Handlers => { Start => sub { shift; shift; %attr = @_ } }
    );

    eval { $p->parse($doc) };
    ok( !$@, 'windows-1250: parse succeeded' ) or diag $@;

    is( $attr{a}, chr(0x0160), 'windows-1250: 0x8A -> U+0160 (S-caron)' );
    is( $attr{b}, chr(0x0161), 'windows-1250: 0x9A -> U+0161 (s-caron)' );
    is( $attr{c}, chr(0x010C), 'windows-1250: 0xC8 -> U+010C (C-caron)' );
}

# ---------- unknown encoding error ----------

{
    my $doc = qq(<?xml version='1.0' encoding='x-bogus-encoding' ?>\n<doc/>);

    my $p = XML::Parser->new;

    eval { $p->parse($doc) };
    like( $@, qr/encmap|encoding/i, 'unknown encoding produces error' );
}

done_testing;
