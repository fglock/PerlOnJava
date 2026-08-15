use strict;
use warnings;
use Test::More;

plan skip_all => 'PerlOnJava Java XS bridge test' unless $^X =~ /jperl/;
plan tests => 4;

require XSLoader;
XSLoader::load('Math::Cephes', '0.5308');

ok(abs(Math::Cephesc::ndtr(0) - 0.5) < 1e-12, 'normal CDF at zero');
ok(abs(Math::Cephesc::ndtr(1.96) - 0.9750021049) < 1e-8, 'normal CDF');
ok(abs(Math::Cephesc::ndtri(0.975) - 1.9599639845) < 1e-8, 'inverse normal CDF');
ok(abs(Math::Cephesc::chdtrc(1, 7.0756) - 0.007813) < 1e-4, 'chi-square upper tail');
