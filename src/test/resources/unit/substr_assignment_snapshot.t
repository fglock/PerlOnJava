#!perl -T
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(tainted);

my $source = '1abc';
my $snapshot = substr($source, 0, 1);
$source = '2def';
is($snapshot, '1', 'direct scalar assignment stores a substr snapshot');
is($snapshot + 0, 1, 'snapshot preserves numeric string value');

my $tainted = substr($^X, 0, 0);
ok(tainted($tainted), 'direct scalar assignment preserves substr taint');

done_testing;
