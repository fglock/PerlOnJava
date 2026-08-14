use strict;
use warnings;
use Test::More tests => 8;

"a" =~ /a/;
ok !("b" =~ //), 'literal empty match reuses the last successful pattern';

my $empty_qr = qr//;
"a" =~ /a/;
ok "b" =~ /$empty_qr/, 'an interpolated empty qr does not reuse the last pattern';

my $patterns = { empty => qr// };
"a" =~ /a/;
ok "b" =~ /$patterns->{empty}/,
  'an empty qr behind a hash dereference keeps its own origin';

my $empty_string = '';
"a" =~ /a/;
ok !("b" =~ /$empty_string/),
  'a dynamically empty string still reuses the last pattern';

$_ = 'b';
"a" =~ /a/;
is s//x/r, 'b', 'literal empty substitution reuses the last pattern';

$_ = 'b';
"a" =~ /a/;
is s/$empty_qr/x/r, 'xb', 'an interpolated empty qr substitutes at the start';

$_ = 'b';
"a" =~ /a/;
is s/$patterns->{empty}/x/r,
  'xb', 'an empty qr hash value substitutes at the start';

$_ = 'b';
"a" =~ /a/;
is s/$empty_string/x/r,
  'b', 'a dynamically empty string substitution reuses the last pattern';
