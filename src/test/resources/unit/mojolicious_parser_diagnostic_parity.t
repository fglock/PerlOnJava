use strict;
use warnings;
use Test::More tests => 3;

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
