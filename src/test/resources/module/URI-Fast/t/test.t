use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri);
use URI::Fast::Test;

my $u1 = 'http://www.example.com/foo/bar?foo=bar#bazbat';
my $u2 = 'https://test.com/asdf?qwerty';

subtest 'URI::Fast instances' => sub{
  is_same_uri uri($u1), uri($u1), 'is_same_uri';
  isnt_same_uri uri($u1), uri($u2), 'isnt_same_uri';
};

subtest 'Strings' => sub{
  is_same_uri $u1, $u1, 'is_same_uri';
  isnt_same_uri $u1, $u2, 'isnt_same_uri';
};

done_testing;
