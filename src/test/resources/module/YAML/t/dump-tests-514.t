use strict;
use lib -e 't' ? 't' : 'test';
use Test::More;
BEGIN {
  unless ( qr/x/ =~ /\(\?\^/ ){
    plan skip_all => "test only for perls v5.13.5-11-gfb85c04 or later";
  }
}
use TestYAML tests => 1;

no_diff();
run_roundtrip_nyn('dumper');

__DATA__
===
+++ no_round_trip
Since we don't use eval for regexp reconstitution any more (for safety
sake) this test doesn't roundtrip even though the values are equivalent.
+++ perl
[qr{bozo$}i]
+++ yaml
---
- !!perl/regexp (?^i:bozo$)

