use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 1;

my ($child_fh, $child_name) = tempfile(SUFFIX => '.pl', UNLINK => 1);
print {$child_fh} 'exit defined(<STDIN>) ? 42 : 0;' . "\n";
close $child_fh;

my $status = system(qq{"$^X" "$child_name"});

is($status, 0, 'string-form system child observes EOF on noninteractive stdin');
