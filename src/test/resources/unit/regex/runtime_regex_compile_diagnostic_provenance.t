use strict;
use warnings;
use Test::More tests => 11;

my @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };

my $source = "BEGIN{\$^H=0x200000}\ns/[(?{//xx";
my $result = eval $source;
my $error = $@;

ok(!defined($result), 'runtime executable regex source fails compilation');
is(scalar(@warnings), 0, 'compile diagnostic is fatal rather than a warning');
like($error, qr/^Unmatched \[ in regex;/,
     'fatal category retains the unmatched character-class diagnostic');
like($error, qr/marked by <-- HERE in m\/\[ <-- HERE \(\?\{\//,
     'diagnostic marker points after the unmatched opening bracket');
like($error, qr/ at \(eval \d+\) line 1\.\n\z/,
     'runtime regex compiler owns an eval source beginning at line one');
unlike($error, qr/ line 2\./,
       'outer regex operator line does not replace runtime-source line one');
unlike($error, qr/ at - line /,
       'runtime-source failure does not fall back to the fresh program file');

@warnings = ();
eval "BEGIN{\$^H=0x200000}\ns/[//";
like($@, qr/ at \(eval \d+\) line 2\.\n\z/,
     'ordinary malformed regex retains its outer operator line');

@warnings = ();
my $closed = eval "BEGIN{\$^H=0x200000}\nqr/[(?{]/xx";
ok(defined($closed),
   'executable-looking text in a closed character class stays literal');
is($@, '', 'closed character class has no fatal diagnostic');
is(scalar(@warnings), 0, 'closed character class has no warning diagnostic');
