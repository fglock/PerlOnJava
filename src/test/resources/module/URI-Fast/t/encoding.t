use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Encode::XS qw(uri_encode_utf8 uri_decode_utf8);
use URI::Fast qw(uri);

my $url       = 'https://test.com/some/path?aaaa=bbbb&cccc=dddd&eeee=ffff';
my $reserved  = q{! * ' ( ) ; : @ & = + $ , / ? # [ ] %};
my $utf8      = "Ῥόδος¢€";
my $malformed = 'p%EErl%E1%BF%AC+%CF%82';

subtest 'basics' => sub{
  is URI::Fast::encode('asdf'), 'asdf', 'non-reserved';
  is URI::Fast::encode('&', '&'), '&', 'allowed';

  is URI::Fast::encode('asdf'), 'asdf', 'non-reserved';

  is(URI::Fast::encode($_), sprintf('%%%02X', ord($_)), "reserved char $_")
    foreach split ' ', $reserved;

  is URI::Fast::decode(URI::Fast::encode($reserved)), $reserved, 'decode';

  is URI::Fast::encode(" &", "&"), "%20&", "encode: allowed chars";

  is URI::Fast::encode(undef), "", "encode: undef";
  is URI::Fast::decode(undef), "", "decode: undef";

  is URI::Fast::decode('%3f'), '?', 'decode: lower cased hex values';
};

subtest 'negative path' => sub {
  is URI::Fast::decode("foo %"), "foo %", "terminal %";
  is URI::Fast::decode("% foo"), "% foo", "leading %";
  is URI::Fast::encode(''), '', 'encode empty string';
  is URI::Fast::decode(''), '', 'decode empty string';
};

subtest 'aliases' => sub{
  my $enc = URI::Fast::encode($reserved);
  is $enc, URI::Fast::uri_encode($reserved), 'uri_encode';
  is URI::Fast::decode($enc), URI::Fast::uri_decode($enc), 'uri_encode';
};

subtest 'utf8' => sub{
  my $u = "Ῥόδος";
  my $a = '%E1%BF%AC%CF%8C%CE%B4%CE%BF%CF%82';

  is URI::Fast::encode('$'), uri_encode_utf8('$'), '1 byte';
  is URI::Fast::encode('¢'), uri_encode_utf8('¢'), 'encode_utf8: 2 bytes';
  is URI::Fast::encode('€'), uri_encode_utf8('€'), 'encode_utf8: 3 bytes';
  is URI::Fast::encode('􏿿'), uri_encode_utf8('􏿿'), 'encode_utf8: 4 bytes';
  is URI::Fast::encode($u), $a, 'encode_utf8: string';

  is URI::Fast::encode($u), $a, 'encode';
  ok !utf8::is_utf8(URI::Fast::encode($u)), 'encode: result is not flagged utf8';

  is URI::Fast::decode($a), $u, 'decode';

  is URI::Fast::decode(lc $a), $u, 'decode lower case';

  ok my $uri = uri($url), 'ctor';

  is $uri->auth("$u:$u\@www.$u.com:1234"), "$u:$u\@www.$u.com:1234", 'auth';
  is $uri->raw_auth, "$a:$a\@www.$a.com:1234", 'raw_auth';

  is $uri->usr, $u, 'usr';
  is $uri->raw_usr, $a, 'raw_usr';

  is $uri->pwd, $u, 'pwd';
  is $uri->raw_pwd, $a, 'raw_pwd';

  is $uri->host, "www.$u.com", 'host';
  is $uri->raw_host, "www.$a.com", 'raw_host';

  is $uri->path("/$u/$u"), "/$u/$u", "path";
  is $uri->raw_path, "/$a/$a", "raw_path";

  is $uri->path([$u, $a]), "/$u/" . URI::Fast::encode($a), "path";
  is $uri->raw_path, "/$a/" . URI::Fast::encode($a), "raw_path";

  is $uri->query("x=$u"), "x=$u", "query";
  is $uri->param('x'), $u, 'param';
  is scalar($uri->raw_query), "x=$a", "raw_query";

  is $uri->query({x => $u}), "x=$u", "query";
  is $uri->param('x'), $u, 'param';
  is scalar($uri->raw_query), "x=$a", "raw_query";

  ok my $mal = URI::Fast::decode($malformed), 'decode: malformed';
  ok !utf8::is_utf8($mal), 'decode: utf8 flag not set when malformed';
};

subtest 'structured data' => sub{
  my $orig = {
    foo => ['bar baz', 'bat%fnord'],
    bar => undef,
    baz => {bat => 'fnord%slack'},
  };

  my $obj = {
    foo => ['bar baz', 'bat%fnord'],
    bar => undef,
    baz => {bat => 'fnord%slack'},
  };

  URI::Fast::escape_tree($obj);

  is $obj, hash {
    field foo => array { item 'bar%20baz'; item 'bat%25fnord'; end; };
    field bar => U;
    field baz => hash { field bat => 'fnord%25slack'; end; };
    end;
  }, 'structure escaped in place';

  URI::Fast::unescape_tree($obj);
  is $obj, $orig, 'structure unescaped in place';

  my $circular = { foo => {bar => 'baz bat'} };
  $circular->{foo}{fnord} = $circular->{foo};
  URI::Fast::escape_tree($circular);
  is $circular->{foo}{fnord}{bar}, 'baz%20bat', 'circular reference is escaped once';

  ok dies{ URI::Fast::escape_tree('non-reference scalar value') }, 'escape_tree dies when called with non-reference scalar value';
  ok dies{ URI::Fast::unescape_tree('non-reference scalar value') }, 'unescape_tree dies when called with non-reference scalar value';
};

done_testing;
