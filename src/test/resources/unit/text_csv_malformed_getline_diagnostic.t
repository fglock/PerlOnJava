use strict;
use warnings;
use Test::More;
use Text::CSV;

my $input = "valid,row\n\"unterminated,row\n";
open my $fh, '<', \$input or die "open scalar input: $!";
my $csv = Text::CSV->new({ binary => 1 });
my $first = $csv->getline($fh);
is_deeply($first, ['valid', 'row'], 'reads the valid row first');
my $malformed = $csv->getline($fh);
ok(!defined $malformed, 'malformed row returns undef');
like("" . $csv->error_diag, qr/EIQ - Quoted field not terminated/,
     'error_diag retains the malformed row diagnostic');

close $fh;
done_testing;
