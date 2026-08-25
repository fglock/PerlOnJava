use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 6;

my $source = <<'PERL';
package LoaderDiagnosticParity;

use strict;

sub new { }

foo {

1;
PERL
chop $source;

my $error = do {
    local $@;
    eval $source;
    $@;
};

like $error,
    qr/^Missing right curly or square bracket at \(eval \d+\) line 9, at end of line\n/,
    'incomplete bareword block reports the missing closing delimiter';
like $error,
    qr/^syntax error at \(eval \d+\) line 9, at EOF$/m,
    'incomplete bareword block reports EOF syntax context';
unlike $error, qr/, near ""/,
    'EOF diagnostic does not replace delimiter detail with empty near context';

my ($handle, $file) = tempfile(SUFFIX => '.pm', UNLINK => 1);
print {$handle} <<'PERL';
package LoadedDiagnosticParity;

use strict;

sub new { }

foo {

1;
PERL
close $handle;

my $loaded_error = do {
    local $@;
    do $file;
    $@;
};

like $loaded_error,
    qr/^Missing right curly or square bracket at \Q$file\E line 9, at end of line\n/,
    'loaded source reports the last content line for a missing delimiter';
like $loaded_error,
    qr/^syntax error at \Q$file\E line 9, at EOF$/m,
    'loaded source keeps the last content line for EOF syntax context';
unlike $loaded_error, qr/\Q$file\E line 10/,
    'loaded source does not report the line after a trailing newline';
