use Test::More tests => 2;
use XML::Parser;
use strict;

# Test that bare glob filehandles (*FH) work with parse().
# See https://github.com/cpan-authors/XML-Parser/issues/201
#
# Since the GH#27 fix, bare globs were no longer recognized as filehandles
# because ref($arg) is '' (not 'GLOB') and the stringified form (*main::FH)
# fails the bareword identifier regex due to the leading '*'.

my $count = 0;
my $parser = XML::Parser->new(
    Handlers => { Comment => sub { $count++ } }
);

# Test bare glob via package-qualified name
{
    no strict 'refs';
    open *main::XMLTEST_FH, '<', 'samples/REC-xml-19980210.xml'
        or die "Cannot open sample: $!";
    $parser->parse(*main::XMLTEST_FH);
    close XMLTEST_FH;
    ok($count > 0, 'bare glob filehandle (*FH) parses correctly');
}

# Test bare glob via unqualified name
{
    $count = 0;
    open XMLTEST_FH2, '<', 'samples/REC-xml-19980210.xml'
        or die "Cannot open sample: $!";
    $parser->parse(*XMLTEST_FH2);
    close XMLTEST_FH2;
    ok($count > 0, 'bare glob filehandle (unqualified *FH) parses correctly');
}
