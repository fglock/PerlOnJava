use strict;
use warnings;
use Test::More tests => 2;

my $ok = eval { die qr/example/; 1 };
ok(!$ok && $@ eq '(?^:example)', 'die with a regex has no appended source location');

my $output = qx{$^X -e 'die qr/example/' 2>&1};
chomp $output;
is($output, '(?^:example)', 'an unhandled regex die has no stack trace');
