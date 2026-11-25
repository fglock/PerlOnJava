use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
eval { require Text::CSV; 1 } or plan skip_all => 'Text::CSV is unavailable';
plan tests => 2;

my ($input_fh, $input_file) = tempfile();
print {$input_fh} "name,value\nalpha,1\n";
close $input_fh or die "close CSV input: $!";
my $rows = Text::CSV::csv(in => $input_file);
is(ref($rows), 'ARRAY', 'functional csv interface returns parsed rows');
is_deeply($rows, [['name', 'value'], ['alpha', '1']],
          'functional csv interface parses through the selected XS shim');
