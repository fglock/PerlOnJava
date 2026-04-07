#!/usr/bin/perl

# Test that XML::Parser works correctly without LWP.
# LWP::UserAgent is an optional dependency; the parser must
# fall back to file-based external entity handling gracefully.
# See GitHub issue #101.

use strict;
use warnings;

use Test::More tests => 4;
use File::Temp qw(tempfile);

use XML::Parser;

# Create a temporary entity file
my ($fh, $entfile) = tempfile(UNLINK => 1, SUFFIX => '.ent');
print $fh "entity content";
close $fh;

my $xml = <<"XML";
<!DOCTYPE foo [
  <!ENTITY ext SYSTEM "$entfile">
]>
<foo>&ext;</foo>
XML

# Test 1-2: NoLWP option forces file-based handler and works
{
    my $chardata = '';
    my $p = XML::Parser->new(
        NoLWP    => 1,
        Handlers => { Char => sub { $chardata .= $_[1] } },
    );

    eval { $p->parse($xml) };
    is($@, '', 'NoLWP: parsing with file entity does not die');
    is($chardata, 'entity content', 'NoLWP: file-based entity content is correct');
}

# Test 3-4: Simulate LWP not installed by setting the load-failed flag
{
    local $XML::Parser::LWP_load_failed = 1;
    my $chardata = '';
    my $p = XML::Parser->new(
        Handlers => { Char => sub { $chardata .= $_[1] } },
    );

    eval { $p->parse($xml) };
    is($@, '', 'LWP_load_failed: parsing with file entity does not die');
    is($chardata, 'entity content', 'LWP_load_failed: file-based entity content is correct');
}
