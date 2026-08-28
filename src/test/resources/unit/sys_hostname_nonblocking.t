use strict;
use warnings;
use Test::More;
use Sys::Hostname qw(hostname);

my $host = hostname();
ok(defined($host) && length($host), 'hostname returns promptly with a value');

done_testing();
