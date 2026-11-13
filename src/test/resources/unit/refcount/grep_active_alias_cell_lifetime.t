use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 2;

{
    package DBICTest::BaseResultSet;
}

my $resultset = bless {}, 'DBICTest::BaseResultSet';
my $weak_resultset = $resultset;
weaken($weak_resultset);
my $source = { resultsets => [$resultset] };
undef $resultset;

my @selected = grep {
    $source->{resultsets} = [];
    ok(defined $weak_resultset,
        'active grep alias retains the referent after source replacement');
    $_->{cursor} ||= {};
    ok(defined $_->{cursor}, 'active grep alias remains usable');
    1;
} @{$source->{resultsets}};
