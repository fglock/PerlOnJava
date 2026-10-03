use strict;
use warnings;
use Test::More;

my $system_status;
my $system_ok = eval { $system_status = system(); 1 };
ok($system_ok && defined $system_status, 'system() without a command returns a status');

my $text = 'foo';
open my $scalar_fh, '<', \$text or die "open scalar handle: $!";
getc $scalar_fh;
is(getc($scalar_fh), 'o', 'scalar-backed getc reads remaining content');
is(getc($scalar_fh), 'o', 'scalar-backed getc reads final content');
is(getc($scalar_fh), undef, 'scalar-backed getc returns undef at EOF');

done_testing();
