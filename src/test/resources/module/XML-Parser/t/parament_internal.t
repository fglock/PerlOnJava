#!/usr/bin/perl

# Test for GitHub issue #53 (rt.cpan.org #80567):
# Parameter entity references in internal subset break parser.
# After a PE reference like %common;, the default handler gets called
# for everything instead of the dedicated handlers (Attlist, etc.).

use strict;
use warnings;

use Test::More tests => 6;
use XML::Parser;

# XML with a parameter entity reference in the internal subset.
# The key issue: after %common;, the Attlist handler should still fire
# for the ATTLIST declaration, not the Default handler.
my $xml_with_pe = <<'EOF';
<!DOCTYPE mytype [
<!ENTITY % common SYSTEM "common.txt">
%common;
<!ATTLIST mytype foo CDATA "bar">
]>
<mytype foo="bar"/>
EOF

# Same XML without the PE reference (control case)
my $xml_without_pe = <<'EOF';
<!DOCTYPE mytype [
<!ATTLIST mytype foo CDATA "bar">
]>
<mytype foo="bar"/>
EOF

# Track which handlers are called
my @attlist_calls;
my @default_calls;
my @doctype_calls;

sub reset_tracking {
    @attlist_calls  = ();
    @default_calls  = ();
    @doctype_calls  = ();
}

sub attlist_handler {
    my ($xp, $elname, $attname, $type, $default, $fixed) = @_;
    push @attlist_calls, { elname => $elname, attname => $attname, default => $default };
}

sub default_handler {
    my ($xp, $string) = @_;
    push @default_calls, $string;
}

sub doctype_handler {
    my ($xp, $name, $sysid, $pubid, $internal) = @_;
    push @doctype_calls, { name => $name, internal => $internal };
}

# Test 1-2: Control case (no PE reference) - Attlist handler should fire
reset_tracking();
my $p = XML::Parser->new(
    NoExpand => 1,
    Handlers => {
        Default    => \&default_handler,
        Doctype    => \&doctype_handler,
        Attlist    => \&attlist_handler,
    },
);

$p->parse($xml_without_pe);
is(scalar @attlist_calls, 1, 'Without PE: Attlist handler called once');
is($attlist_calls[0]{attname}, 'foo', 'Without PE: Attlist got correct attribute name');

# Test 3-6: With PE reference - Attlist handler should STILL fire
# (This is the bug: after %common;, everything goes to Default handler)
reset_tracking();
$p = XML::Parser->new(
    NoExpand => 1,
    Handlers => {
        Default    => \&default_handler,
        Doctype    => \&doctype_handler,
        Attlist    => \&attlist_handler,
    },
);

$p->parse($xml_with_pe);
is(scalar @doctype_calls, 1, 'With PE: Doctype handler called');
is($doctype_calls[0]{name}, 'mytype', 'With PE: Doctype got correct name');
is(scalar @attlist_calls, 1, 'With PE: Attlist handler called once (not routed to Default)');
is($attlist_calls[0]{attname}, 'foo', 'With PE: Attlist got correct attribute name');
