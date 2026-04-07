use Test::More tests => 4;
use XML::Parser;
use strict;

# Test that parsing XML strings does not auto-vivify symbol table entries.
# See https://github.com/cpan-authors/XML-Parser/issues/27
# and rt.cpan.org #7630
#
# On older Perls (< 5.22), `defined *{$str}` auto-vivifies a symbol table
# entry for $str, causing a memory leak when $str is XML content rather than
# a filehandle name. The fix guards the glob lookup with a regex check that
# ensures $str looks like a valid Perl identifier before attempting the lookup.

my $parser = XML::Parser->new(
    Handlers => { Start => sub {} }
);

# Simple XML string
{
    my $xml = '<a><b>Sea</b></a>';
    $parser->parsestring($xml);
    ok(!exists $::{$xml}, 'simple XML string does not auto-vivify symbol table entry');
}

# XML string containing :: (interpreted as package separator)
{
    my $xml = '<a><b::c>text</b::c></a>';
    eval { $parser->parsestring($xml); };
    # Parse may fail due to namespace issues, but should not leak
    ok(!exists $::{$xml}, 'XML string with :: does not auto-vivify symbol table entry');
}

# XML string with quotes (interpreted as package separators in older Perl)
{
    my $xml = q{<root attr="val">content</root>};
    $parser->parsestring($xml);
    ok(!exists $::{$xml}, 'XML string with quotes does not auto-vivify symbol table entry');
}

# Verify that actual filehandle parsing still works
{
    my $count = 0;
    my $p = XML::Parser->new(
        Handlers => { Comment => sub { $count++ } }
    );
    open my $fh, '<', 'samples/REC-xml-19980210.xml' or die "Cannot open sample: $!";
    $p->parse($fh);
    close $fh;
    ok($count > 0, 'parsing from filehandle still works');
}
