################################################################################
#
# Copyright (c) Marcus Holland-Moritz. All rights reserved.
# This program is free software; you can redistribute it and/or modify
# it under the same terms as Perl itself.
#
################################################################################

use Test;

BEGIN { $tests = 25; plan tests => $tests };

use Tie::Hash::Indexed;
ok(1);

eval { require Storable; import Storable qw( dclone freeze thaw ) };
if ($@) {
  for (2..$tests) { skip("skip: Storable not installed", 0, 1) }
  exit;
}
if (eval $Storable::VERSION < 1.011) {
  for (2..$tests) { skip("skip: Storable $Storable::VERSION is buggy", 0, 1) }
  exit;
}

@keys = reverse 'a' .. 'z';

$r = do {
  my %h;
  tie %h, 'Tie::Hash::Indexed';
  my $i = 1;
  %h = map { $_ => $i++ } @keys;
  dclone(\%h);
};

{
  my $k = join(',', @keys);
  my $v = join(',', 1..@keys);
  ok(join(',', keys %$r), $k);
  ok(join(',', values %$r), $v);
  my $frozen = freeze($r);
  my $thawed = thaw($frozen);
  ok(join(',', keys %$thawed), $k);
  ok(join(',', values %$thawed), $v);
}

$r = do {
  my(%h1, %h2, %h3);
  tie %h1, 'Tie::Hash::Indexed';
  tie %h2, 'Tie::Hash::Indexed';
  tie %h3, 'Tie::Hash::Indexed';
  %h1 = ( foo => 1, bar => 'indexed', mhx => undef );
  %h2 = ( h1 => \%h1, zzz => undef, aaa => [1 .. 3] );
  %h3 = ( this => 42, hash => { h1 => \%h1, h2 => \%h2 }, is => undef, indexed => [\%h2] );
  dclone(\%h3);
};

{
  my $frozen = freeze($r);
  my $thawed = thaw($frozen);
  for my $x ( $r, $thawed ) {
    ok(join(',', keys %$x), 'this,hash,is,indexed');
    ok(join(',', keys %{$x->{indexed}[0]}), 'h1,zzz,aaa');
    ok(join(',', keys %{$x->{indexed}[0]{h1}}), 'foo,bar,mhx');
    ok(not defined $x->{is});
    ok(not defined $x->{hash}{h1}{mhx});
    ok(not defined $x->{hash}{h2}{zzz});
    ok($x->{this}, 42);
    ok($x->{hash}{h1}, $x->{hash}{h2}{h1});
    ok($x->{hash}{h2}, $x->{indexed}[0]);
    ok(join(',', @{$x->{hash}{h2}{aaa}}), '1,2,3');
  }
}
