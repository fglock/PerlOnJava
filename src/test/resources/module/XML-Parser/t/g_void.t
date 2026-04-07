#!/usr/bin/perl

# Verify that all callback handlers work correctly with G_VOID|G_DISCARD.
# Each handler modified by the G_VOID change is exercised and checked
# for correct invocation and argument passing.

use strict;
use warnings;

use Test::More;
use XML::Parser;

# Track which handlers were called and with what arguments
my %called;

# --- Handler subs ---

sub h_start {
    my ($p, $el, %atts) = @_;
    $called{Start}++;
    $called{Start_el} = $el if $el eq 'root' || $el eq 'child';
    $called{Start_att} = $atts{id} if defined $atts{id};
}

sub h_end {
    my ($p, $el) = @_;
    $called{End}++;
    $called{End_el} = $el if $el eq 'root';
}

sub h_char {
    my ($p, $str) = @_;
    $called{Char}++ if $str =~ /\S/;
    $called{Char_data} .= $str;
}

sub h_proc {
    my ($p, $target, $data) = @_;
    $called{Proc}++;
    $called{Proc_target} = $target;
    $called{Proc_data} = $data;
}

sub h_comment {
    my ($p, $str) = @_;
    $called{Comment}++;
    $called{Comment_data} = $str;
}

sub h_cdata_start {
    my ($p) = @_;
    $called{CdataStart}++;
}

sub h_cdata_end {
    my ($p) = @_;
    $called{CdataEnd}++;
}

sub h_default {
    my ($p, $str) = @_;
    $called{Default}++;
}

# --- Test 1: Basic handlers (Char, Start, End, Proc, Comment, CdataStart, CdataEnd, Default) ---

my $doc1 = <<'XML';
<?xml version="1.0"?>
<root id="test1">
  <?mytarget mydata?>
  <!-- a comment -->
  <child>Hello world</child>
  <![CDATA[cdata content]]>
</root>
XML

%called = ();
my $p1 = XML::Parser->new(
    Handlers => {
        Start      => \&h_start,
        End        => \&h_end,
        Char       => \&h_char,
        Proc       => \&h_proc,
        Comment    => \&h_comment,
        CdataStart => \&h_cdata_start,
        CdataEnd   => \&h_cdata_end,
    }
);
$p1->parse($doc1);

ok($called{Start} && $called{Start} >= 2, 'Start handler called for elements');
is($called{Start_att}, 'test1', 'Start handler receives attributes');
ok($called{End} && $called{End} >= 2, 'End handler called');
is($called{End_el}, 'root', 'End handler receives element name');
ok($called{Char}, 'Char handler called');
like($called{Char_data}, qr/Hello world/, 'Char handler receives text content');
like($called{Char_data}, qr/cdata content/, 'Char handler receives CDATA text');
is($called{Proc}, 1, 'Proc handler called once');
is($called{Proc_target}, 'mytarget', 'Proc handler receives target');
like($called{Proc_data}, qr/mydata/, 'Proc handler receives data');
is($called{Comment}, 1, 'Comment handler called once');
like($called{Comment_data}, qr/a comment/, 'Comment handler receives comment text');
is($called{CdataStart}, 1, 'CdataStart handler called');
is($called{CdataEnd}, 1, 'CdataEnd handler called');

# --- Test 2: Default handler ---

%called = ();
my $p2 = XML::Parser->new(
    Handlers => {
        Default => \&h_default,
    }
);
$p2->parse('<root>text</root>');
ok($called{Default} && $called{Default} > 0, 'Default handler called');

# --- Test 3: Declaration handlers (Entity, Element, Attlist, Doctype, DoctypeFin, XMLDecl) ---

my %decl;

sub h_entity {
    my ($p, $name, $val, $sys, $pub, $notation) = @_;
    $decl{Entity}++;
    $decl{Entity_name} = $name if defined $name;
    $decl{Entity_val}  = $val  if defined $val && $name eq 'myent';
}

sub h_element {
    my ($p, $name, $model) = @_;
    $decl{Element}++;
    $decl{Element_name} = $name if $name eq 'item';
}

sub h_attlist {
    my ($p, $elname, $attname, $type, $default, $fixed) = @_;
    $decl{Attlist}++;
    $decl{Attlist_el}  = $elname;
    $decl{Attlist_att} = $attname;
}

sub h_doctype {
    my ($p, $name, $sys, $pub, $internal) = @_;
    $decl{Doctype}++;
    $decl{Doctype_name} = $name;
}

sub h_doctype_fin {
    my ($p) = @_;
    $decl{DoctypeFin}++;
}

sub h_xmldecl {
    my ($p, $version, $encoding, $standalone) = @_;
    $decl{XMLDecl}++;
    $decl{XMLDecl_version} = $version;
}

# Need ParseParamEnt for internal DTD subset processing
my $probe = XML::Parser->new(ParseParamEnt => 1, NoLWP => 1, ErrorContext => 2);
eval { $probe->parse("<?xml version=\"1.0\"?>\n<!DOCTYPE foo SYSTEM \"t/foo.dtd\" []>\n<foo/>\n") };
my $can_parse_param_ent = !$@;

SKIP: {
    skip "expat cannot process external DTD with parameter entities", 9
        unless $can_parse_param_ent;

    my $doc3 = <<'XML';
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE root [
  <!ENTITY myent "hello">
  <!ELEMENT root (#PCDATA|item)*>
  <!ELEMENT item (#PCDATA)>
  <!ATTLIST item type CDATA #IMPLIED>
]>
<root>&myent;<item type="x">data</item></root>
XML

    %decl = ();
    my $p3 = XML::Parser->new(
        ParseParamEnt => 1,
        NoLWP         => 1,
        Handlers      => {
            Entity     => \&h_entity,
            Element    => \&h_element,
            Attlist    => \&h_attlist,
            Doctype    => \&h_doctype,
            DoctypeFin => \&h_doctype_fin,
            XMLDecl    => \&h_xmldecl,
        }
    );
    $p3->parse($doc3);

    ok($decl{Entity}, 'Entity handler called');
    is($decl{Entity_val}, 'hello', 'Entity handler receives value');
    ok($decl{Element}, 'Element handler called');
    is($decl{Element_name}, 'item', 'Element handler receives element name');
    ok($decl{Attlist}, 'Attlist handler called');
    is($decl{Attlist_att}, 'type', 'Attlist handler receives attribute name');
    ok($decl{Doctype}, 'Doctype handler called');
    is($decl{Doctype_name}, 'root', 'Doctype handler receives doctype name');
    ok($decl{DoctypeFin}, 'DoctypeFin handler called');
}

# XMLDecl can be tested independently
%decl = ();
my $p3b = XML::Parser->new(
    Handlers => {
        XMLDecl => \&h_xmldecl,
    }
);
$p3b->parse('<?xml version="1.0"?><r/>');
ok($decl{XMLDecl}, 'XMLDecl handler called');
is($decl{XMLDecl_version}, '1.0', 'XMLDecl handler receives version');

# --- Test 4: Unparsed entity and Notation handlers ---

my %ext;

sub h_notation {
    my ($p, $name, $base, $sysid, $pubid) = @_;
    $ext{Notation}++;
    $ext{Notation_name} = $name;
}

sub h_unparsed {
    my ($p, $name, $base, $sysid, $pubid, $notation) = @_;
    $ext{Unparsed}++;
    $ext{Unparsed_name}     = $name;
    $ext{Unparsed_notation} = $notation;
}

my $doc4 = <<'XML';
<?xml version="1.0"?>
<!DOCTYPE root [
  <!NOTATION gif SYSTEM "image/gif">
  <!ENTITY logo SYSTEM "logo.gif" NDATA gif>
]>
<root/>
XML

%ext = ();
my $p4 = XML::Parser->new(
    Handlers => {
        Notation => \&h_notation,
        Unparsed => \&h_unparsed,
    }
);
$p4->parse($doc4);

ok($ext{Notation}, 'Notation handler called');
is($ext{Notation_name}, 'gif', 'Notation handler receives notation name');
ok($ext{Unparsed}, 'Unparsed handler called');
is($ext{Unparsed_name}, 'logo', 'Unparsed handler receives entity name');
is($ext{Unparsed_notation}, 'gif', 'Unparsed handler receives notation');

# --- Test 5: ExternEnt and ExternEntFin handlers ---

my %ent;

sub h_extern_ent {
    my ($p, $base, $sysid, $pubid) = @_;
    $ent{ExternEnt}++;
    return "external content";
}

sub h_extern_ent_fin {
    my ($p) = @_;
    $ent{ExternEntFin}++;
}

my $doc5 = <<'XML';
<?xml version="1.0"?>
<!DOCTYPE root [
  <!ENTITY ext SYSTEM "ext.txt">
]>
<root>&ext;</root>
XML

%ent = ();
my $p5 = XML::Parser->new(
    Handlers => {
        ExternEnt    => \&h_extern_ent,
        ExternEntFin => \&h_extern_ent_fin,
    }
);
$p5->parse($doc5);

ok($ent{ExternEnt}, 'ExternEnt handler called');
ok($ent{ExternEntFin}, 'ExternEntFin handler called');

# --- Test 6: Namespace handlers (NamespaceStart/NamespaceEnd via perl_call_method) ---

my %ns;

{
    # NamespaceStart and NamespaceEnd are called as methods on the parser
    # object when Namespaces mode is enabled. We subclass to intercept them.
    package NSTester;
    our @ISA = ('XML::Parser::Expat');

    sub NamespaceStart {
        my ($self, $prefix, $uri) = @_;
        $ns{NsStart}++;
        $ns{NsStart_uri} = $uri if defined $uri;
    }

    sub NamespaceEnd {
        my ($self, $prefix) = @_;
        $ns{NsEnd}++;
    }
}

# NamespaceStart/NamespaceEnd are called internally by Expat when
# Namespaces => 1 is set. We verify they fire by checking the parser's
# namespace tracking functions.
my $doc6 = <<'XML';
<root xmlns:ns="urn:test:ns" xmlns="urn:test:default">
  <ns:child/>
</root>
XML

%ns = ();
my $ns_start_count = 0;
my $ns_end_count = 0;

my $p6 = XML::Parser->new(
    Namespaces => 1,
    Handlers   => {
        Start => sub {
            my ($p, $el, %atts) = @_;
            if ($el eq 'root') {
                # If namespace handlers fired, we should see prefixes
                my @prefixes = $p->new_ns_prefixes;
                $ns_start_count = scalar @prefixes;
            }
        },
        End => sub {
            my ($p, $el) = @_;
            if ($el eq 'root') {
                $ns_end_count++;
            }
        },
    }
);
$p6->parse($doc6);

ok($ns_start_count >= 1, 'Namespace processing works (new_ns_prefixes reported)');
ok($ns_end_count, 'End handler works in namespace mode');

done_testing();
