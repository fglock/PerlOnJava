#!/usr/bin/perl

# Test that lexical filehandles (open my $fh) work as return values
# from ExternEnt handlers. See GitHub issue #44 / rt.cpan.org #36096.

use strict;
use warnings;

use Test::More;
use XML::Parser;
use File::Temp qw(tempfile);

if ($] < 5.012) {
    plan skip_all => 'Lexical filehandles lack read() method before Perl 5.12';
}
plan tests => 2;

# Create a temporary entity file
my ($fh, $entfile) = tempfile(UNLINK => 1, SUFFIX => '.ent');
print $fh "hello world";
close $fh;

my $xml = <<"XML";
<!DOCTYPE foo [
  <!ENTITY ext SYSTEM "$entfile">
]>
<foo>&ext;</foo>
XML

# Test 1: lexical glob returned directly (open my $fh)
{
    my $chardata = '';
    my $p = XML::Parser->new(
        Handlers => {
            Char => sub { $chardata .= $_[1] },
            ExternEnt => sub {
                my ($xp, $base, $sysid, $pubid) = @_;
                open my $efh, '<', $sysid or die "Cannot open $sysid: $!";
                return $efh;
            },
            ExternEntFin => sub { },  # no-op cleanup
        },
    );

    eval { $p->parse($xml) };
    is($@, '', 'parsing with lexical glob ExternEnt handler does not die');
    is($chardata, 'hello world', 'character data from lexical glob entity is correct');
}
