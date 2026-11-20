use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);

subtest 'basics' => sub{
  ok((my $uri = uri 'http://www.test.com'), 'uri');

  $uri->query_keyset({foo => 1, bar => 1, baz => 1, bat => 1});
  is [sort $uri->query_keys], [qw(bar bat baz foo)], 'set';

  $uri->query_keyset({bar => 0});
  is [sort $uri->query_keys], [qw(bat baz foo)], 'remove w/ 0';

  $uri->query_keyset({baz => undef});
  is [sort $uri->query_keys], [qw(bat foo)], 'remove w/ undef';
};

subtest 'mixed' => sub {
  ok((my $uri = uri 'http://www.test.com?foo=bar&baz=bat'), 'uri');

  $uri->query_keyset({foo => 0});
  is $uri->query_hash, {baz => ['bat']}, 'remove key=val';

  $uri->query_keyset({foo => 1, fnord => 1});
  is $uri->query_hash, {baz => ['bat'], foo => [], fnord => []}, 'update';

  $uri->query_keyset({foo => undef});
  is $uri->query_hash, {baz => ['bat'], fnord => []}, 'remove key w/ undef';

  $uri->query_keyset({fnord => 0});
  is $uri->query_hash, {baz => ['bat']}, 'remove key w/ 0';
};

subtest 'separator replacement' => sub {
  my $uri = uri 'http://example.com';

  $uri->query_keyset({foo => 1, bar => 1});
  like $uri->query, qr/&/, 'separator defaults to &';

  $uri->query_keyset({baz => 1}, ';');
  like $uri->query, qr/;/, 'explicit separator used';
  unlike $uri->query, qr/&/, 'original separator replaced';
};

subtest 'empty string' => sub {
  my $uri = uri 'http://example.com/?foo&bar';
  $uri->query_keyset({'' => 1, bar => 0});
  is $uri->query, 'foo', 'empty string does not break query';
};

done_testing;
