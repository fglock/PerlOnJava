use utf8;
use ExtUtils::testlib;
use Test2::V0;
use URI::Fast qw(uri abs_uri);

my $base = 'http://a/b/c/d;p?q';

subtest 'normal cases - https://www.rfc-editor.org/rfc/rfc3986.txt 5.4.1' => sub{
  my @cases = (
    ["g"       , "http://a/b/c/g"],
    ["./g"     , "http://a/b/c/g"],
    ["g/"      , "http://a/b/c/g/"],
    ["/g"      , "http://a/g"],
    ["//g"     , "http://g"],
    ["?y"      , "http://a/b/c/d;p?y"],
    ["g?y"     , "http://a/b/c/g?y"],
    ["#s"      , "http://a/b/c/d;p?q#s"],
    ["g#s"     , "http://a/b/c/g#s"],
    ["g?y#s"   , "http://a/b/c/g?y#s"],
    [";x"      , "http://a/b/c/;x"],
    ["g;x"     , "http://a/b/c/g;x"],
    ["g;x?y#s" , "http://a/b/c/g;x?y#s"],
    [""        , "http://a/b/c/d;p?q"],
    ["."       , "http://a/b/c/"],
    ["./"      , "http://a/b/c/"],
    [".."      , "http://a/b/"],
    ["../"     , "http://a/b/"],
    ["../g"    , "http://a/b/g"],
    ["../.."   , "http://a/"],
    ["../../"  , "http://a/"],
    ["../../g" , "http://a/g"],
  );

  foreach my $test (@cases) {
    my ($rel, $exp) = @$test;

    my $abs = uri($rel)->absolute(uri($base));
    is $abs, $exp, "absolute: $rel -> $exp"
      or do{
        diag "rel:    '$rel'";
        diag "base:   '$base'";
        diag "exp:    '$exp'";
        diag "actual: '$abs'";
      };

    my $abs_uri = abs_uri($rel, $base);
    is $abs_uri, $exp, "abs_uri: $rel -> $exp"
      or do{
        diag "rel:    '$rel'";
        diag "base:   '$base'";
        diag "exp:    '$exp'";
        diag "actual: '$abs_uri'";
      };

    my $new_abs = URI::Fast->new_abs($rel, $base);
    is $new_abs, $exp, "new_abs: $rel -> $exp"
      or do{
        diag "rel:    '$rel'";
        diag "base:   '$base'";
        diag "exp:    '$exp'";
        diag "actual: '$new_abs'";
      };
  }
};

subtest 'edge cases - https://www.rfc-editor.org/rfc/rfc3986.txt 5.4.2' => sub{
  my @cases = (
    ["../../../g",    "http://a/g"],
    ["../../../../g", "http://a/g"],
    ["/./g",          "http://a/g"],
    ["/../g",         "http://a/g"],
    ["g.",            "http://a/b/c/g."],
    [".g",            "http://a/b/c/.g"],
    ["g..",           "http://a/b/c/g.."],
    ["..g",           "http://a/b/c/..g"],
    ["./../g",        "http://a/b/g"],
    ["./g/.",         "http://a/b/c/g/"],
    ["g/./h",         "http://a/b/c/g/h"],
    ["g/../h",        "http://a/b/c/h"],
    ["g;x=1/./y",     "http://a/b/c/g;x=1/y"],
    ["g;x=1/../y",    "http://a/b/c/y"],
    ["g?y/./x",       "http://a/b/c/g?y/./x"],
    ["g?y/../x",      "http://a/b/c/g?y/../x"],
    ["g#s/./x",       "http://a/b/c/g#s/./x"],
    ["g#s/../x",      "http://a/b/c/g#s/../x"],
  );

  foreach my $test (@cases) {
    my ($rel, $exp) = @$test;

    my $abs = uri($rel)->absolute(uri($base));
    is $abs, $exp, "absolute: $rel -> $exp"
      or do{
        diag "rel:    '$rel'";
        diag "base:   '$base'";
        diag "exp:    '$exp'";
        diag "actual: '$abs'";
        bail_out;
      };

    my $new_abs = URI::Fast->new_abs($rel, $base);
    is $new_abs, $exp, "new_abs: $rel -> $exp"
      or do{
        diag "rel:    '$rel'";
        diag "base:   '$base'";
        diag "exp:    '$exp'";
        diag "actual: '$new_abs'";
      };
  }
};

done_testing;
