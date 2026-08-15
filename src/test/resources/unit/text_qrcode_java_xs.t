use strict;
use warnings;
use Test::More;

plan skip_all => 'PerlOnJava Java XS bridge test' unless $^X =~ /jperl/;
plan tests => 4;

require XSLoader;
XSLoader::load('Text::QRCode', '0.05');

my $matrix = Text::QRCode::_plot('Some text here.', {});
is(ref($matrix), 'ARRAY', '_plot returns an array reference');
is(scalar(@$matrix), 21, 'version 1 QR code has 21 rows');
is(scalar(@{$matrix->[0]}), 21, 'version 1 QR code has 21 columns');
is(join('', @{$matrix->[0]}), '******* *  ** *******',
    'QR matrix matches the libqrencode reference first row');
