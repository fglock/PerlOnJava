use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 4;

{
    package DBICTest::BaseResultSet;
}

my $resultset = bless {}, 'DBICTest::BaseResultSet';
my $weak_resultset = $resultset;
weaken($weak_resultset);
my $source = { resultsets => [$resultset] };
undef $resultset;

my @mapped = map {
    $source->{resultsets} = [];
    ok(defined $weak_resultset,
    'map alias retains the scalar cell after the source array is replaced');
    $_->{cursor} ||= {};
    ok(defined $_->{cursor}, 'the active map alias remains usable');
    $_;
} @{$source->{resultsets}};

ok(defined $weak_resultset, 'the map result retains the resultset');
@mapped = ();
ok(!defined $weak_resultset, 'clearing the map result releases the final owner');
