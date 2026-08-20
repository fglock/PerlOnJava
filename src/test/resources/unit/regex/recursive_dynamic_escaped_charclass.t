use strict;
use warnings;
use Test::More tests => 2;
use re 'eval';

our @recursive;
my $dynamic = '(??{$main::recursive [1]})';
my $compiled = qr/\((?:(?>[^\(\)]+)|$dynamic)*\)/;
$main::recursive[1] = $compiled;

ok('((text))' =~ /\A$compiled\z/,
    'escaped first character-class member does not hide a dynamic callback');
ok('(text)' =~ /\A$compiled\z/,
    'recursive dynamic regexp still matches a non-nested value');
