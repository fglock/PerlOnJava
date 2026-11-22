use utf8;
use ExtUtils::testlib;
use Test2::V0;
use Data::Dumper;
use URI::Split qw();
use URI::Fast qw();
use URI qw();

my @uris = (
  # From URI::Split's test suite
  'p',
  'p?q',
  'p?q/#f/?',
  's://a/p?q#f',
  '<undef>',
  's://a/p?q#f',
  's://a/p?q#f',

  # Extra cases
  '/foo/bar/baz',
  'file:///foo/bar/baz',
  'http://www.test.com',
  'http://www.test.com?foo=bar',
  'http://www.test.com#bar',
  'http://www.test.com/some/path',
  'https://test.com/some/path?aaaa=bbbb&cccc=dddd&eeee=ffff',
  'https://user:pwd@192.168.0.1:8000/foo/bar?baz=bat&slack=fnord&asdf=the+quick%20brown+fox+%26+hound#foofrag',
  'https://user:pwd@www.test.com:8000/foo/bar?baz=bat&slack=fnord&asdf=the+quick%20brown+fox+%26+hound#foofrag',
  '//foo/bar',
);

for my $str (@uris) {
  my $orig = [ URI::Split::uri_split($str) ];
  my $xs   = [ URI::Fast::uri_split($str)  ];
  is $xs, $orig, $str or diag Dumper $xs;
}

done_testing;
