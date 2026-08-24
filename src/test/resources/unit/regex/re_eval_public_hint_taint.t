#!perl -T
use strict;
use warnings;
use Scalar::Util qw(tainted);
use Test::More tests => 4;

BEGIN { $^H |= 0x00200000 }

my $empty_taint = substr($^X, 0, 0);
ok(tainted($empty_taint), 'fixture obtains tainted provenance');

my $runtime = "(?{})$empty_taint";
ok(tainted($runtime), 'runtime regex source remains tainted');

my $matched = eval { 'a' =~ /$runtime/; 1 };
ok(!$matched, 'public re-eval hint does not admit tainted runtime source');
like($@, qr/^Eval-group in insecure regular expression/,
    'tainted public-hint source retains the security diagnostic');
