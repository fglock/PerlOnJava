#!/usr/bin/perl

use strict;
use warnings;

use Test::More tests => 5;
use XML::Parser;

# Test that ErrorContext enhances exceptions thrown during parsing
# with XML line number and context information.
# See https://github.com/cpan-authors/XML-Parser/issues/70

# Test 1-2: With ErrorContext, an exception thrown during parsing
# gets XML line/context info appended.
{
    my $p = XML::Parser->new(
        ErrorContext => 2,
        Handlers     => {
            ExternEnt => sub {
                my ( $expat, $base, $sysid ) = @_;
                open( my $fh, '<', $sysid ) or die "No such file or directory";
                return $fh;
            },
        },
    );
    my $xml = <<'XML';
<!DOCTYPE doc [
<!ENTITY foo SYSTEM "missing_file.ent">
]>
<doc>&foo;</doc>
XML
    eval { $p->parse($xml) };
    my $err = $@;
    ok( $err, "exception during parsing is caught" );
    like( $err, qr/at line \d+/, "with ErrorContext, exception includes XML line number" );
}

# Test 3: Without ErrorContext, exceptions propagate unchanged
{
    my $p = XML::Parser->new(
        Handlers => {
            ExternEnt => sub {
                my ( $expat, $base, $sysid ) = @_;
                open( my $fh, '<', $sysid ) or die "No such file or directory";
                return $fh;
            },
        },
    );
    my $xml = <<'XML';
<!DOCTYPE doc [
<!ENTITY foo SYSTEM "missing_file.ent">
]>
<doc>&foo;</doc>
XML
    eval { $p->parse($xml) };
    my $err = $@;
    unlike( $err, qr/at line \d+:/, "without ErrorContext, no XML context added" );
}

# Test 4: Handler die with ref exception is preserved as-is
{
    my $p = XML::Parser->new(
        ErrorContext => 2,
        Handlers     => {
            Start => sub {
                die { code => 42, message => 'custom error' };
            },
        },
    );
    eval { $p->parse('<doc/>') };
    my $err = $@;
    is( ref($err), 'HASH', "ref exception from handler preserved with ErrorContext" );
}

# Test 5: Handler string exception gets context with ErrorContext
{
    my $p = XML::Parser->new(
        ErrorContext => 2,
        Handlers     => {
            Start => sub {
                die "handler error\n";
            },
        },
    );
    eval { $p->parse('<doc/>') };
    my $err = $@;
    like( $err, qr/handler error/, "string exception from handler propagates" );
}
