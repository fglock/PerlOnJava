#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 2;
use B::Deparse;

my $deparse = B::Deparse->new;

my $dbic_generated = <<'PERL';
  { use strict; use warnings; use warnings FATAL => 'uninitialized';
$_ = [{ "year" => $_->[1] }] for @{$_[0]}
  }
PERL

my $dbic_expected = <<'PERL';
  { use strict; use warnings FATAL => 'uninitialized';
$_ = [
    { year => $_->[1] },
  ] for @{$_[0]}
  }
PERL

my @dbic_like = map {
    my $cref = eval "sub { $_ }" or die $@;
    $deparse->coderef2text($cref);
} ($dbic_generated, $dbic_expected);

is($dbic_like[0], $dbic_like[1], 'pragma-wrapped eval snippets do not compare raw source');
is($dbic_like[0], '{ "DUMMY" }', 'complex eval source falls back to placeholder');
