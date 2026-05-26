use strict;
use warnings;
use Test::More tests => 1;

my $line = <DATA>;
is($line, "payload\n", '__END__ in a top-level script populates main::DATA');

package DataSectionEndPackage;

__END__
payload
