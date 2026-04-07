#!/usr/bin/perl

use strict;
use warnings;

use Test::More tests => 5;
use XML::Parser;

ok("loaded");

# Test that unescaped '<' in content gives a helpful hint
{
    my $p = XML::Parser->new();
    eval { $p->parse("<base>\n<StreetOne>221 <atteson Ct.</StreetOne>\n</base>") };
    my $err = $@;
    like($err, qr/not well-formed/, "unescaped '<' triggers parse error");
    like($err, qr/&lt;/, "error message hints about &lt; escaping");
}

# Test that unescaped '&' in content gives a helpful hint
{
    my $p = XML::Parser->new();
    eval { $p->parse("<base><item>AT&T</item></base>") };
    my $err = $@;
    like($err, qr/not well-formed/, "unescaped '&' triggers parse error");
    like($err, qr/&amp;/, "error message hints about &amp; escaping");
}
