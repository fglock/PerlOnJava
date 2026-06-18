use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    require feature;
    feature->import('fc');
}

is fc('ABC'), 'abc', 'BEGIN-time feature->import enables fc for later parsing';

{
    package BeginFeatureImportSub;
    sub fc { 'sub' }
}

is BeginFeatureImportSub::fc('ABC'), 'sub', 'ordinary sub named fc still works when called qualified';
