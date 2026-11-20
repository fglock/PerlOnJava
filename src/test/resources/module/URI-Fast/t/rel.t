use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

my $uri1 = 'http://www.example.com/foo/bar/';
my $uri2 = 'http://www.example.com/foo/bar';

my @tests = (
  [$uri1, 'http://www.example.com/foo/bar/',    './'],
  [$uri1, 'HTTP://WWW.EXAMPLE.COM/foo/bar/',    './'],
  [$uri1, 'HTTP://WWW.EXAMPLE.COM/FOO/BAR/',    '../../foo/bar/'],
  [$uri1, 'HTTP://WWW.EXAMPLE.COM:80/foo/bar/', './'],
  [$uri2, 'http://www.example.com/foo/bar',     'bar'],
  [$uri2, 'http://www.example.com/foo',         'foo/bar'],
);

foreach my $test (@tests) {
  my ($uri, $base, $exp) = @$test;
  my $rel = uri($uri)->relative($base);
  is $rel, $exp, "uri='$uri' base='$base' rel='$exp'"
    or do{
      diag "uri:      '$uri'";
      diag "base:     '$base'";
      diag "expected: '$exp'";
      diag "actual:   '$rel'";
    };  
}

done_testing;
