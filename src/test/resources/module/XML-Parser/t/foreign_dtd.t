#!/usr/bin/perl

use strict;
use warnings;

use Test::More;
use XML::Parser;

# Test UseForeignDTD option which allows providing a DTD for documents
# without a DOCTYPE declaration via the ExternalEntityRef handler.

# Verify expat can handle external DTD processing with parameter entities.
my $probe = XML::Parser->new(ParseParamEnt => 1, NoLWP => 1, ErrorContext => 2);
eval { $probe->parse("<?xml version=\"1.0\"?>\n<!DOCTYPE foo SYSTEM \"t/foo.dtd\" []>\n<foo/>\n") };
if ($@) {
    plan skip_all => "expat cannot process external DTD with parameter entities: $@";
}

plan tests => 5;

# Create a DTD file that defines a default attribute and an entity
my $dtd_file = 't/foreign.dtd';
open(my $fh, '>', $dtd_file) or die "Cannot write $dtd_file: $!";
print $fh <<'DTD';
<!ELEMENT doc (#PCDATA)>
<!ATTLIST doc class CDATA "default_value">
<!ENTITY greeting "Hello from foreign DTD">
DTD
close($fh);

# Document WITHOUT a DOCTYPE declaration
my $doc = <<'XML';
<?xml version="1.0"?>
<doc>&greeting;</doc>
XML

# Test 1: UseForeignDTD with custom ExternEnt handler
{
    my $char_data = '';
    my %attrs;

    my $p = XML::Parser->new(
        UseForeignDTD => 1,
        ParseParamEnt => 1,
        ErrorContext   => 2,
        Handlers       => {
            ExternEnt => sub {
                my ($xp, $base, $sysid, $pubid) = @_;
                # For foreign DTD, sysid and pubid are undef
                ok(!defined $sysid, 'sysid is undef for foreign DTD');
                require IO::File;
                my $fh = IO::File->new($dtd_file);
                return $fh;
            },
            ExternEntFin => sub { },
            Start => sub {
                my ($xp, $el, %a) = @_;
                %attrs = %a if $el eq 'doc';
            },
            Char => sub {
                my ($xp, $text) = @_;
                $char_data .= $text;
            },
        }
    );

    eval { $p->parse($doc) };
    is($@, '', 'parse succeeded with UseForeignDTD');
    is($attrs{class}, 'default_value', 'default attribute from foreign DTD applied');
    is($char_data, 'Hello from foreign DTD', 'entity from foreign DTD expanded');
}

# Test 2: Without UseForeignDTD, entity reference should fail
{
    my $p = XML::Parser->new(
        ErrorContext => 2,
        Handlers     => {
            ExternEnt    => sub { return undef },
            ExternEntFin => sub { },
        }
    );

    eval { $p->parse($doc) };
    like($@, qr/undefined entity/, 'without UseForeignDTD, entity is undefined');
}

unlink($dtd_file);
